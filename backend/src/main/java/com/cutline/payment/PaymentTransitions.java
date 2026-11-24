package com.cutline.payment;

import com.cutline.order.OrderStatus;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제와 주문의 상태 전이를 모은 곳. 결제 확정 API와 복구 작업이 같은 코드를 쓴다.
 *
 * <p>잠금 규칙: 주문과 결제를 함께 바꾸는 트랜잭션은 항상 주문 행을 먼저 잠근다 ({@code SELECT ... FOR UPDATE}).
 * 점유 만료도 주문 행을 먼저 바꾸고 결제 테이블을 읽으므로, 순서가 같아 서로 엇갈려 기다리지 않는다.
 * 결제 행만 바꾸는 전이(UNKNOWN, FAILED, CANCELED)는 문장 하나라 순서 문제가 없다.
 */
@Component
class PaymentTransitions {

    enum Settlement {
        /** 주문이 PAID로 바뀌었다. */
        PAID,
        /** 결제는 승인됐는데 주문은 이미 만료·취소됐다. PG 취소(보상)가 필요하다. */
        NEEDS_REFUND,
        /** 다른 요청이나 복구 작업이 먼저 처리했다. */
        ALREADY_SETTLED
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    PaymentTransitions(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * PG가 승인했다는 사실을 반영한다. PG 승인이 진실이므로 FAILED였던 결제도 APPROVED로 올린다
     * (복구 작업이 "없음"으로 판단한 뒤 늦게 도착한 승인).
     */
    Settlement settleApproved(long orderId, String paymentKey) {
        return tx.execute(status -> {
            Timestamp now = now();
            lockOrder(orderId);
            int payment = jdbc.update("""
                    UPDATE payment SET status = 'APPROVED', pg_payment_key = ?, approved_at = ?, updated_at = ?
                    WHERE order_id = ? AND status IN ('REQUESTED', 'UNKNOWN', 'FAILED')""",
                    paymentKey, now, now, orderId);
            if (payment == 0) {
                return Settlement.ALREADY_SETTLED;
            }
            int order = jdbc.update("""
                    UPDATE orders SET status = 'PAID', version = version + 1, updated_at = ?
                    WHERE id = ? AND status = 'PENDING_PAYMENT'""", now, orderId);
            return order == 1 ? Settlement.PAID : Settlement.NEEDS_REFUND;
        });
    }

    void markUnknown(long orderId) {
        jdbc.update("UPDATE payment SET status = 'UNKNOWN', updated_at = ? WHERE order_id = ? AND status = 'REQUESTED'",
                now(), orderId);
    }

    void markFailed(long orderId) {
        jdbc.update("""
                UPDATE payment SET status = 'FAILED', updated_at = ?
                WHERE order_id = ? AND status IN ('REQUESTED', 'UNKNOWN')""", now(), orderId);
    }

    /** PG 취소가 끝난 뒤 기록한다. 주문이 PAID인 결제는 이 경로로 취소하지 않는다(환불은 별도 흐름). */
    void markRefunded(long orderId) {
        tx.executeWithoutResult(status -> {
            lockOrder(orderId);
            jdbc.update("""
                    UPDATE payment p JOIN orders o ON o.id = p.order_id
                    SET p.status = 'CANCELED', p.updated_at = ?
                    WHERE p.order_id = ? AND p.status <> 'CANCELED' AND o.status <> 'PAID'""", now(), orderId);
        });
    }

    PaymentResult current(long orderId) {
        List<PaymentResult> rows = jdbc.query("""
                SELECT o.status AS order_status, p.status AS payment_status
                FROM orders o LEFT JOIN payment p ON p.order_id = o.id WHERE o.id = ?""",
                (rs, i) -> new PaymentResult(orderId, OrderStatus.valueOf(rs.getString("order_status")),
                        rs.getString("payment_status") == null ? null
                                : PaymentStatus.valueOf(rs.getString("payment_status"))),
                orderId);
        return rows.getFirst();
    }

    void lockOrder(long orderId) {
        jdbc.queryForList("SELECT id FROM orders WHERE id = ? FOR UPDATE", Long.class, orderId);
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }
}
