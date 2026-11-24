package com.cutline.order;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 대기 시간이 지난 주문을 만료시키고, 잡아 두었던 재고와 구매 한도를 돌려준다 (ADR-005).
 *
 * <p>주문 하나당 트랜잭션 하나:
 * <ol>
 *   <li>{@code UPDATE orders SET status = 'EXPIRED' WHERE id = ? AND status = 'PENDING_PAYMENT' ...}</li>
 *   <li>1행이 바뀐 경우에만 재고(drop_event)와 구매 한도(member_drop_quota)를 되돌린다.</li>
 * </ol>
 * 서버가 여러 대라 같은 주문을 동시에 집어도, 상태 전이는 한 트랜잭션만 성공하므로 복구는 한 번만 일어난다.
 *
 * <p>결제가 UNKNOWN(PG 응답 유실)인 주문은 건너뛴다. 돈이 실제로 나갔을 수 있어서, 만료시키면
 * "결제는 됐는데 주문은 만료"가 생긴다. 이런 주문은 PG 조회로 결제 결과를 확정한 뒤 처리한다 (6~7일차).
 *
 * <p>재고·한도 복구는 {@link HoldRelease}가 한다 (잠금 순서 drop_event → member_drop_quota).
 */
@Service
public class OrderExpiryService {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryService.class);

    /** 한 번 실행에서 최대 몇 번까지 배치를 반복할지. 오픈 직후 만료가 몰려도 한 번에 끝없이 돌지 않게 한다. */
    private static final int MAX_BATCHES_PER_RUN = 50;

    private static final String NO_UNKNOWN_PAYMENT =
            "NOT EXISTS (SELECT 1 FROM payment p WHERE p.order_id = o.id AND p.status = 'UNKNOWN')";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final HoldRelease holdRelease;
    private final Clock clock;
    private final int batchSize;
    private final AtomicLong failures = new AtomicLong();

    public OrderExpiryService(JdbcTemplate jdbc, TransactionTemplate tx, HoldRelease holdRelease, Clock clock,
                              @Value("${cutline.order.expiry.batch-size:100}") int batchSize) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.holdRelease = holdRelease;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /** 만료 시각이 지난 주문을 모두 처리하고, 이번 실행에서 실제로 만료시킨 주문 수를 돌려준다. */
    public int expireDue() {
        Instant now = clock.instant();
        int expired = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            List<Long> ids = findDue(now);
            for (Long id : ids) {
                if (expireOne(id, now)) {
                    expired++;
                }
            }
            if (ids.size() < batchSize) {
                break;
            }
        }
        return expired;
    }

    private List<Long> findDue(Instant now) {
        return jdbc.queryForList("""
                SELECT o.id FROM orders o
                WHERE o.status = 'PENDING_PAYMENT' AND o.hold_expires_at <= ? AND %s
                ORDER BY o.hold_expires_at
                LIMIT ?""".formatted(NO_UNKNOWN_PAYMENT), Long.class, Timestamp.from(now), batchSize);
    }

    /**
     * 주문 하나를 만료시킨다. 다른 서버가 먼저 처리했거나 그 사이 결제가 끝났으면 false.
     * 한 주문의 실패가 나머지 주문 처리를 막지 않도록 예외는 기록만 하고 넘어간다.
     */
    boolean expireOne(long orderId, Instant now) {
        try {
            return Boolean.TRUE.equals(tx.execute(status -> transition(orderId, now)));
        } catch (RuntimeException e) {
            failures.incrementAndGet();
            log.error("order {} expiry failed, will retry next run", orderId, e);
            return false;
        }
    }

    private boolean transition(long orderId, Instant now) {
        Timestamp ts = Timestamp.from(now);
        int changed = jdbc.update("""
                UPDATE orders o
                SET o.status = 'EXPIRED', o.version = o.version + 1, o.updated_at = ?
                WHERE o.id = ? AND o.status = 'PENDING_PAYMENT' AND o.hold_expires_at <= ? AND %s"""
                .formatted(NO_UNKNOWN_PAYMENT), ts, orderId, ts);
        if (changed == 0) {
            return false;
        }

        holdRelease.release(orderId);
        return true;
    }

    /** 시작 후 예외로 실패한 만료 시도 수. 데드락처럼 조용히 삼켜지는 실패를 테스트와 모니터링에서 보기 위한 값. */
    public long failureCount() {
        return failures.get();
    }
}
