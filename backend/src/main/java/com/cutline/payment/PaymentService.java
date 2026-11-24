package com.cutline.payment;

import com.cutline.common.error.CutlineException;
import com.cutline.common.error.ErrorCode;
import com.cutline.order.OrderStatus;
import com.cutline.payment.pg.PaymentGateway;
import com.cutline.payment.pg.PgResult;
import com.cutline.payment.pg.PgTimeoutException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 확정 (설계 문서 "결제 확정", ADR-006).
 *
 * <p>PG 호출은 DB 트랜잭션 밖에서 한다. PG가 몇 초씩 느려져도 DB 커넥션과 잠금을 붙잡지 않기 위해서다.
 * <ol>
 *   <li>트랜잭션 1: 주문을 잠그고 검증한 뒤 결제를 REQUESTED로 기록 (주문당 결제 1건, order_id UNIQUE)</li>
 *   <li>PG 승인 호출 (주문 ID가 PG 쪽 멱등 키)</li>
 *   <li>트랜잭션 2: 승인이면 결제 APPROVED + 주문 PAID. 그 사이 주문이 만료됐으면 PG 취소로 보상</li>
 * </ol>
 * 응답이 없으면 결제를 UNKNOWN으로 두고 202를 돌려준다. {@link PaymentReconciler}가 PG 조회로 확정한다.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PaymentGateway gateway;
    private final PaymentTransitions transitions;
    private final Clock clock;

    PaymentService(JdbcTemplate jdbc, TransactionTemplate tx, PaymentGateway gateway,
                   PaymentTransitions transitions, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.gateway = gateway;
        this.transitions = transitions;
        this.clock = clock;
    }

    public PaymentResult confirm(long memberId, long orderId, String paymentKey, long amount) {
        PaymentResult done = tx.execute(status -> begin(memberId, orderId, paymentKey, amount));
        if (done != null) {
            return done;
        }

        PgResult result;
        try {
            result = gateway.approve(paymentKey, orderId, amount);
        } catch (PgTimeoutException e) {
            log.warn("payment result unknown for order {}: {}", orderId, e.getMessage());
            transitions.markUnknown(orderId);
            return new PaymentResult(orderId, OrderStatus.PENDING_PAYMENT, PaymentStatus.UNKNOWN);
        }

        if (result.status() != PgResult.Status.APPROVED) {
            transitions.markFailed(orderId);
            throw new CutlineException(ErrorCode.PAYMENT_DECLINED);
        }

        return switch (transitions.settleApproved(orderId, result.paymentKey())) {
            case PAID -> new PaymentResult(orderId, OrderStatus.PAID, PaymentStatus.APPROVED);
            case NEEDS_REFUND -> {
                refund(orderId, result.paymentKey());
                throw new CutlineException(ErrorCode.HOLD_EXPIRED, "결제 대기 시간이 지나 승인된 결제를 취소했습니다.");
            }
            case ALREADY_SETTLED -> transitions.current(orderId);
        };
    }

    /**
     * 승인됐지만 주문이 이미 끝난(만료·취소·환불) 결제를 PG에서 취소한다. 실패하면 결제가 APPROVED로 남고,
     * 복구 작업이 "APPROVED인데 주문이 PAID가 아닌 결제"를 찾아 다시 취소한다.
     */
    void refund(long orderId, String paymentKey) {
        try {
            gateway.cancel(paymentKey, orderId, "order is no longer payable (expired or canceled)");
            transitions.markRefunded(orderId);
        } catch (RuntimeException e) {
            log.error("refund for order {} failed, reconciler will retry", orderId, e);
        }
    }

    /** 트랜잭션 1. 이미 PAID면 그 결과를, 진행해야 하면 null을 돌려준다. */
    private PaymentResult begin(long memberId, long orderId, String paymentKey, long amount) {
        List<OrderRow> rows = jdbc.query("""
                SELECT member_id, amount, status, hold_expires_at FROM orders WHERE id = ? FOR UPDATE""",
                (rs, i) -> new OrderRow(rs.getLong("member_id"), rs.getLong("amount"),
                        OrderStatus.valueOf(rs.getString("status")), rs.getTimestamp("hold_expires_at").toInstant()),
                orderId);
        if (rows.isEmpty() || rows.getFirst().memberId() != memberId) {
            throw new CutlineException(ErrorCode.ORDER_NOT_FOUND);
        }
        OrderRow order = rows.getFirst();
        OrderStatus status = order.status();
        Instant now = clock.instant();

        if (status == OrderStatus.PAID) {
            return new PaymentResult(orderId, OrderStatus.PAID, PaymentStatus.APPROVED);
        }
        if (status == OrderStatus.EXPIRED) {
            throw new CutlineException(ErrorCode.HOLD_EXPIRED);
        }
        if (status != OrderStatus.PENDING_PAYMENT) {
            throw new CutlineException(ErrorCode.ORDER_NOT_PAYABLE);
        }
        if (!now.isBefore(order.holdExpiresAt())) {
            throw new CutlineException(ErrorCode.HOLD_EXPIRED);
        }
        if (order.amount() != amount) {
            throw new CutlineException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }

        Timestamp ts = Timestamp.from(now);
        // 잠금 없는 읽기로 충분하다. 결제 행은 이 메서드에서만, 주문 행 잠금을 쥔 채로 만들어지므로 "있는지"는 정확하다.
        // 여기서 FOR UPDATE를 쓰면 없는 행에 갭 잠금이 걸려, 이웃 주문들의 INSERT끼리 데드락이 난다 (트러블슈팅 003).
        List<String> existing = jdbc.queryForList(
                "SELECT status FROM payment WHERE order_id = ?", String.class, orderId);
        if (existing.isEmpty()) {
            try {
                jdbc.update("""
                        INSERT INTO payment (order_id, pg_payment_key, amount, status, created_at, updated_at)
                        VALUES (?, ?, ?, 'REQUESTED', ?, ?)""", orderId, paymentKey, amount, ts, ts);
            } catch (DuplicateKeyException e) {
                // 주문은 잠가 두었으므로 order_id 중복은 없다. 다른 주문의 paymentKey를 재사용한 경우다.
                throw new CutlineException(ErrorCode.INVALID_REQUEST, "이미 사용된 paymentKey입니다.");
            }
            return null;
        }
        if (PaymentStatus.valueOf(existing.getFirst()) == PaymentStatus.FAILED) {
            // 거절된 뒤 다른 카드로 다시 시도
            int retried = jdbc.update("""
                    UPDATE payment SET status = 'REQUESTED', pg_payment_key = ?, updated_at = ?
                    WHERE order_id = ? AND status = 'FAILED'""", paymentKey, ts, orderId);
            if (retried == 1) {
                return null;
            }
        }
        throw new CutlineException(ErrorCode.PAYMENT_IN_PROGRESS);
    }

    private record OrderRow(long memberId, long amount, OrderStatus status, Instant holdExpiresAt) {
    }
}
