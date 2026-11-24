package com.cutline.order;

import com.cutline.common.error.CutlineException;
import com.cutline.common.error.ErrorCode;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구매자가 주문을 취소한다.
 *
 * <ul>
 *   <li>결제 전(PENDING_PAYMENT) → CANCELED, 재고·한도 복구.
 *       PG 승인이 진행 중이었다면 결제 확정 쪽이 "주문이 이미 끝남"을 보고 승인을 취소한다(보상).</li>
 *   <li>결제 후(PAID) → REFUNDED, 재고·한도 복구, 커밋 뒤 PG 환불 ({@link OrderRefundRequested}).</li>
 *   <li>이미 취소·환불된 주문은 그대로 성공으로 응답한다(멱등). 만료된 주문은 409.</li>
 * </ul>
 * 상태 전이는 만료와 같은 조건부 UPDATE라, 만료와 취소가 동시에 와도 하나만 성공하고 복구는 한 번만 일어난다.
 */
@Service
public class OrderCancelService {

    private final JdbcTemplate jdbc;
    private final HoldRelease holdRelease;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    OrderCancelService(JdbcTemplate jdbc, HoldRelease holdRelease, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.holdRelease = holdRelease;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public OrderStatus cancel(long memberId, long orderId) {
        List<OrderStatus> rows = jdbc.query("SELECT status FROM orders WHERE id = ? AND member_id = ?",
                (rs, i) -> OrderStatus.valueOf(rs.getString("status")), orderId, memberId);
        if (rows.isEmpty()) {
            throw new CutlineException(ErrorCode.ORDER_NOT_FOUND);
        }
        Timestamp now = Timestamp.from(clock.instant());

        if (transition(orderId, OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELED, now)) {
            holdRelease.release(orderId);
            return OrderStatus.CANCELED;
        }
        if (transition(orderId, OrderStatus.PAID, OrderStatus.REFUNDED, now)) {
            holdRelease.release(orderId);
            events.publishEvent(new OrderRefundRequested(orderId));
            return OrderStatus.REFUNDED;
        }

        // 둘 다 아니면 그 사이 다른 전이가 끝난 것. 지금 상태로 판단한다.
        OrderStatus current = OrderStatus.valueOf(
                jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId));
        return switch (current) {
            case CANCELED, REFUNDED -> current;
            default -> throw new CutlineException(ErrorCode.ORDER_NOT_CANCELABLE);
        };
    }

    private boolean transition(long orderId, OrderStatus from, OrderStatus to, Timestamp now) {
        return jdbc.update("""
                UPDATE orders SET status = ?, version = version + 1, updated_at = ?
                WHERE id = ? AND status = ?""", to.name(), now, orderId, from.name()) == 1;
    }
}
