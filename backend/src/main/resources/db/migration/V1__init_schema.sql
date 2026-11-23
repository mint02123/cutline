-- 컷라인 기본 스키마.
-- 정합성 규칙은 애플리케이션보다 먼저 DB 제약으로 건다 (설계 문서 "데이터 모델" 참고).
-- 금액은 원 단위 BIGINT, 시각은 UTC DATETIME(6).

CREATE TABLE seller (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    name         VARCHAR(100) NOT NULL,
    fee_rate_bps INT          NOT NULL COMMENT '수수료율, 1bps = 0.01%',
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_seller_fee_rate CHECK (fee_rate_bps BETWEEN 0 AND 10000)
);

CREATE TABLE member (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_member_email UNIQUE (email)
);

CREATE TABLE product (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    seller_id  BIGINT       NOT NULL,
    name       VARCHAR(200) NOT NULL,
    price      BIGINT       NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_product_seller FOREIGN KEY (seller_id) REFERENCES seller (id),
    CONSTRAINT ck_product_price CHECK (price > 0)
);

CREATE TABLE drop_event (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    product_id       BIGINT      NOT NULL,
    total_qty        INT         NOT NULL,
    remaining_qty    INT         NOT NULL,
    per_member_limit INT         NOT NULL,
    opens_at         DATETIME(6) NOT NULL,
    hold_minutes     INT         NOT NULL,
    status           VARCHAR(20) NOT NULL,
    created_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_drop_event_product FOREIGN KEY (product_id) REFERENCES product (id),
    CONSTRAINT ck_drop_event_total_qty CHECK (total_qty > 0),
    CONSTRAINT ck_drop_event_remaining_min CHECK (remaining_qty >= 0),
    CONSTRAINT ck_drop_event_remaining_max CHECK (remaining_qty <= total_qty),
    CONSTRAINT ck_drop_event_limit CHECK (per_member_limit > 0),
    CONSTRAINT ck_drop_event_hold CHECK (hold_minutes > 0),
    CONSTRAINT ck_drop_event_status CHECK (status IN ('SCHEDULED', 'OPEN', 'CLOSED'))
);

CREATE TABLE member_drop_quota (
    drop_id   BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    used_qty  INT    NOT NULL DEFAULT 0,
    PRIMARY KEY (drop_id, member_id),
    CONSTRAINT fk_quota_drop FOREIGN KEY (drop_id) REFERENCES drop_event (id),
    CONSTRAINT fk_quota_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT ck_quota_used CHECK (used_qty >= 0)
);

CREATE TABLE orders (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    drop_id         BIGINT      NOT NULL,
    member_id       BIGINT      NOT NULL,
    qty             INT         NOT NULL,
    amount          BIGINT      NOT NULL COMMENT '주문 시점 가격 x 수량',
    status          VARCHAR(30) NOT NULL,
    hold_expires_at DATETIME(6) NOT NULL,
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_orders_drop FOREIGN KEY (drop_id) REFERENCES drop_event (id),
    CONSTRAINT fk_orders_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT ck_orders_qty CHECK (qty > 0),
    CONSTRAINT ck_orders_amount CHECK (amount >= 0),
    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'EXPIRED', 'CANCELED', 'REFUNDED')),
    INDEX idx_orders_status_hold (status, hold_expires_at),
    INDEX idx_orders_member_created (member_id, created_at)
);

CREATE TABLE payment (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    order_id       BIGINT       NOT NULL,
    pg_payment_key VARCHAR(200) NULL,
    amount         BIGINT       NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    approved_at    DATETIME(6)  NULL,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT uk_payment_order UNIQUE (order_id),
    CONSTRAINT uk_payment_pg_key UNIQUE (pg_payment_key),
    CONSTRAINT ck_payment_amount CHECK (amount >= 0),
    CONSTRAINT ck_payment_status CHECK (status IN ('REQUESTED', 'APPROVED', 'UNKNOWN', 'FAILED', 'CANCELED'))
);

CREATE TABLE idempotency_key (
    member_id       BIGINT       NOT NULL,
    idem_key        VARCHAR(100) NOT NULL,
    request_hash    CHAR(64)     NOT NULL COMMENT '요청 본문 SHA-256, 같은 키로 다른 요청이 오면 거절',
    status          VARCHAR(20)  NOT NULL,
    response_status INT          NULL,
    response_body   JSON         NULL,
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (member_id, idem_key),
    CONSTRAINT ck_idem_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);
