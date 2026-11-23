package com.cutline.drop;

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
 * 한정 수량 드롭. remaining_qty는 이 엔티티로 바꾸지 않는다.
 * 재고 변경은 항상 조건부 UPDATE 한 번으로 처리한다 (설계 문서 "동시성과 정합성 전략", ADR-002).
 */
@Entity
@Table(name = "drop_event")
public class DropEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "total_qty", nullable = false)
    private int totalQty;

    @Column(name = "remaining_qty", nullable = false, updatable = false)
    private int remainingQty;

    @Column(name = "per_member_limit", nullable = false)
    private int perMemberLimit;

    @Column(name = "opens_at", nullable = false)
    private Instant opensAt;

    @Column(name = "hold_minutes", nullable = false)
    private int holdMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private DropStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DropEvent() {
    }

    private DropEvent(Long productId, int totalQty, int perMemberLimit, Instant opensAt, int holdMinutes, Instant createdAt) {
        this.productId = productId;
        this.totalQty = totalQty;
        this.remainingQty = totalQty;
        this.perMemberLimit = perMemberLimit;
        this.opensAt = opensAt;
        this.holdMinutes = holdMinutes;
        this.status = DropStatus.SCHEDULED;
        this.createdAt = createdAt;
    }

    public static DropEvent schedule(Long productId, int totalQty, int perMemberLimit, Instant opensAt, int holdMinutes, Instant now) {
        if (!opensAt.isAfter(now)) {
            throw new IllegalArgumentException("opensAt must be in the future");
        }
        if (perMemberLimit > totalQty) {
            throw new IllegalArgumentException("perMemberLimit must not exceed totalQty");
        }
        return new DropEvent(productId, totalQty, perMemberLimit, opensAt, holdMinutes, now);
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public int getTotalQty() {
        return totalQty;
    }

    public int getRemainingQty() {
        return remainingQty;
    }

    public int getPerMemberLimit() {
        return perMemberLimit;
    }

    public Instant getOpensAt() {
        return opensAt;
    }

    public int getHoldMinutes() {
        return holdMinutes;
    }

    public DropStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
