package com.cutline.order;

import java.time.Instant;

/**
 * 주문 생성 응답. 멱등 키 재요청 때 이 스냅샷을 그대로 돌려준다.
 */
public record OrderReceipt(Long orderId, Long dropId, int qty, long amount, OrderStatus status, Instant holdExpiresAt) {

    static OrderReceipt of(Order order) {
        return new OrderReceipt(order.getId(), order.getDropId(), order.getQty(), order.getAmount(),
                order.getStatus(), order.getHoldExpiresAt());
    }
}
