package com.cutline.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 주문. 상태(status)는 이 엔티티로 바꾸지 않고 조건부 UPDATE로만 바꾼다
 * ({@code WHERE status = '현재 상태'}). 만료와 결제가 동시에 와도 하나만 성공하게 하기 위함.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "drop_id", nullable = false)
    private Long dropId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private int qty;

    /** 주문 시점 가격 x 수량. 이후 상품 가격이 바뀌어도 정산이 틀어지지 않는다. */
    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private OrderStatus status;

    @Column(name = "hold_expires_at", nullable = false)
    private Instant holdExpiresAt;

    @Column(nullable = false, updatable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Order() {
    }

    public static Order hold(Long dropId, Long memberId, int qty, long unitPrice, Instant holdExpiresAt, Instant now) {
        Order order = new Order();
        order.dropId = dropId;
        order.memberId = memberId;
        order.qty = qty;
        order.amount = Math.multiplyExact(unitPrice, qty);
        order.status = OrderStatus.PENDING_PAYMENT;
        order.holdExpiresAt = holdExpiresAt;
        order.createdAt = now;
        order.updatedAt = now;
        return order;
    }

    public Long getId() {
        return id;
    }

    public Long getDropId() {
        return dropId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public int getQty() {
        return qty;
    }

    public long getAmount() {
        return amount;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
