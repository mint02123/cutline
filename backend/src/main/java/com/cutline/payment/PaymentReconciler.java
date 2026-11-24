package com.cutline.payment;

import com.cutline.payment.pg.PaymentGateway;
import com.cutline.payment.pg.PgResult;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 결과를 모르는 결제를 PG 조회로 확정하고, 못 끝낸 보상 취소를 다시 시도한다 (ADR-006).
 *
 * <p>대상:
 * <ul>
 *   <li>UNKNOWN: PG 응답을 받지 못한 결제</li>
 *   <li>오래된 REQUESTED: PG 호출 직전·도중에 서버가 죽은 결제. PG 호출 타임아웃보다 충분히 긴 시간이 지나야 대상이 된다</li>
 *   <li>APPROVED인데 주문이 PAID가 아닌 결제: 보상 취소가 실패한 건</li>
 * </ul>
 * PG 조회가 다시 실패하면 그대로 두고 다음 실행에서 재시도한다. 모든 전이가 조건부라 여러 대가 동시에 돌아도 된다.
 */
@Service
public class PaymentReconciler {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);
    private static final int BATCH = 100;

    private final JdbcTemplate jdbc;
    private final PaymentGateway gateway;
    private final PaymentTransitions transitions;
    private final PaymentService payments;
    private final Clock clock;
    private final Duration staleRequestedAfter;

    PaymentReconciler(JdbcTemplate jdbc, PaymentGateway gateway, PaymentTransitions transitions,
                      PaymentService payments, Clock clock,
                      @Value("${cutline.payment.reconciler.stale-requested-after:PT30S}") Duration staleRequestedAfter) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.transitions = transitions;
        this.payments = payments;
        this.clock = clock;
        this.staleRequestedAfter = staleRequestedAfter;
    }

    public record Report(int resolved, int refunded, int stillUnknown) {
    }

    public Report reconcile() {
        int resolved = 0;
        int stillUnknown = 0;
        Timestamp staleBefore = Timestamp.from(clock.instant().minus(staleRequestedAfter));
        List<Long> unsettled = jdbc.queryForList("""
                SELECT order_id FROM payment
                WHERE status = 'UNKNOWN' OR (status = 'REQUESTED' AND updated_at <= ?)
                ORDER BY updated_at LIMIT ?""", Long.class, staleBefore, BATCH);
        for (Long orderId : unsettled) {
            if (resolve(orderId)) {
                resolved++;
            } else {
                stillUnknown++;
            }
        }

        int refunded = 0;
        List<RefundTarget> refunds = jdbc.query("""
                SELECT p.order_id, p.pg_payment_key FROM payment p JOIN orders o ON o.id = p.order_id
                WHERE p.status = 'APPROVED' AND o.status <> 'PAID' LIMIT ?""",
                (rs, i) -> new RefundTarget(rs.getLong("order_id"), rs.getString("pg_payment_key")), BATCH);
        for (RefundTarget target : refunds) {
            payments.refund(target.orderId(), target.paymentKey());
            refunded++;
        }
        return new Report(resolved, refunded, stillUnknown);
    }

    private boolean resolve(long orderId) {
        PgResult result;
        try {
            result = gateway.inquire(orderId);
        } catch (RuntimeException e) {
            log.warn("pg inquiry for order {} failed, will retry: {}", orderId, e.getMessage());
            return false;
        }
        switch (result.status()) {
            case APPROVED -> {
                if (transitions.settleApproved(orderId, result.paymentKey()) == PaymentTransitions.Settlement.NEEDS_REFUND) {
                    payments.refund(orderId, result.paymentKey());
                }
            }
            case CANCELED -> transitions.markRefunded(orderId);
            case DECLINED, NOT_FOUND -> transitions.markFailed(orderId);
        }
        return true;
    }

    private record RefundTarget(long orderId, String paymentKey) {
    }
}
