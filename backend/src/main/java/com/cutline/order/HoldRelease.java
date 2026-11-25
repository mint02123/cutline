package com.cutline.order;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 주문이 잡고 있던 재고와 구매 한도를 돌려준다. 만료와 취소가 같이 쓴다.
 *
 * <p>호출하는 쪽은 같은 트랜잭션에서 주문 상태를 먼저 조건부로 바꾸고, 1행이 바뀐 경우에만 이것을 부른다.
 * 그래야 여러 서버가 동시에 같은 주문을 처리해도 복구가 한 번만 일어난다.
 * 잠금 순서는 주문 생성과 같게 drop_event → member_drop_quota (트러블슈팅 002).
 *
 * <p>커밋된 뒤에는 Redis 관문의 토큰도 돌려준다 ({@link GateTokenReturn}). 돌려주지 못해도 Redis가 MySQL보다
 * 적은 상태(덜 파는 쪽)로 남을 뿐이고, 재동기화가 맞춘다 (ADR-009).
 */
@Component
class HoldRelease {

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    HoldRelease(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    void release(long orderId) {
        var order = jdbc.queryForMap("SELECT drop_id, member_id, qty FROM orders WHERE id = ?", orderId);
        long dropId = ((Number) order.get("drop_id")).longValue();
        long memberId = ((Number) order.get("member_id")).longValue();
        int qty = ((Number) order.get("qty")).intValue();

        int stock = jdbc.update(
                "UPDATE drop_event SET remaining_qty = remaining_qty + ? WHERE id = ?", qty, dropId);
        int quota = jdbc.update("""
                UPDATE member_drop_quota SET used_qty = used_qty - ?
                WHERE drop_id = ? AND member_id = ? AND used_qty >= ?""", qty, dropId, memberId, qty);
        if (stock != 1 || quota != 1) {
            // 주문이 있는데 재고·한도 행이 없거나 모자라면 데이터가 이미 틀어진 것이다. 롤백하고 알린다.
            throw new IllegalStateException("order " + orderId + " release mismatch: stock=" + stock + ", quota=" + quota);
        }
        events.publishEvent(new HoldReleased(dropId, memberId, qty));
    }

    record HoldReleased(long dropId, long memberId, int qty) {
    }
}
