package com.cutline.seller;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "seller")
public class Seller {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /** 수수료율. 1bps = 0.01%. 금액 계산에 부동소수점을 쓰지 않기 위해 정수로 저장한다. */
    @Column(name = "fee_rate_bps", nullable = false)
    private int feeRateBps;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Seller() {
    }

    public Seller(String name, int feeRateBps, Instant createdAt) {
        this.name = name;
        this.feeRateBps = feeRateBps;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getFeeRateBps() {
        return feeRateBps;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
