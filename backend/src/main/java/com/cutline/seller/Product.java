package com.cutline.seller;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 다른 애그리거트는 ID로만 참조한다. 연관관계로 묶으면 드롭 조회 때 불필요한 조인이 생긴다. */
    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(nullable = false, length = 200)
    private String name;

    /** 원 단위 정수. */
    @Column(nullable = false)
    private long price;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Product() {
    }

    public Product(Long sellerId, String name, long price, Instant createdAt) {
        this.sellerId = sellerId;
        this.name = name;
        this.price = price;
        this.createdAt = createdAt;
    }

    public boolean isOwnedBy(Long sellerId) {
        return this.sellerId.equals(sellerId);
    }

    public Long getId() {
        return id;
    }

    public Long getSellerId() {
        return sellerId;
    }

    public String getName() {
        return name;
    }

    public long getPrice() {
        return price;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
