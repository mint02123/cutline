package com.cutline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cutline.support.MySqlTestContainer;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 애플리케이션 코드에 버그가 있어도 DB가 마지막으로 막아 주는지 확인한다.
 */
@SpringBootTest
@Import(MySqlTestContainer.class)
class SchemaConstraintsTest {

    @Autowired
    JdbcTemplate jdbc;

    long dropId;
    long memberId;

    @BeforeEach
    void setUp() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : new String[] {"payment", "orders", "member_drop_quota", "drop_event", "product", "member", "seller", "idempotency_key"}) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");

        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO seller (id, name, fee_rate_bps, created_at) VALUES (1, 'shop', 1000, ?)", now);
        jdbc.update("INSERT INTO product (id, seller_id, name, price, created_at) VALUES (1, 1, 'sneaker', 199000, ?)", now);
        jdbc.update("INSERT INTO member (id, email, password_hash, created_at) VALUES (1, 'a@cutline.dev', 'x', ?)", now);
        jdbc.update("""
                INSERT INTO drop_event (id, product_id, total_qty, remaining_qty, per_member_limit, opens_at, hold_minutes, status, created_at)
                VALUES (1, 1, 1, 1, 1, ?, 10, 'OPEN', ?)""", now, now);
        dropId = 1;
        memberId = 1;
    }

    @Test
    void 재고는_0_아래로_내려가지_않는다() {
        jdbc.update("UPDATE drop_event SET remaining_qty = remaining_qty - 1 WHERE id = ?", dropId);

        assertThatThrownBy(() -> jdbc.update("UPDATE drop_event SET remaining_qty = remaining_qty - 1 WHERE id = ?", dropId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT remaining_qty FROM drop_event WHERE id = ?", Integer.class, dropId)).isZero();
    }

    @Test
    void 재고는_총수량을_넘지_않는다() {
        assertThatThrownBy(() -> jdbc.update("UPDATE drop_event SET remaining_qty = remaining_qty + 1 WHERE id = ?", dropId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 주문당_결제는_하나뿐이다() {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO orders (id, drop_id, member_id, qty, amount, status, hold_expires_at, created_at, updated_at)
                VALUES (1, ?, ?, 1, 199000, 'PENDING_PAYMENT', ?, ?, ?)""", dropId, memberId, now.plusMinutes(10), now, now);
        jdbc.update("INSERT INTO payment (order_id, amount, status, created_at, updated_at) VALUES (1, 199000, 'REQUESTED', ?, ?)", now, now);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO payment (order_id, amount, status, created_at, updated_at) VALUES (1, 199000, 'REQUESTED', ?, ?)", now, now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 정의되지_않은_주문_상태는_저장되지_않는다() {
        LocalDateTime now = LocalDateTime.now();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO orders (drop_id, member_id, qty, amount, status, hold_expires_at, created_at, updated_at)
                VALUES (?, ?, 1, 199000, 'SHIPPED', ?, ?, ?)""", dropId, memberId, now, now, now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 같은_멱등_키는_한_번만_등록된다() {
        String sql = "INSERT INTO idempotency_key (member_id, idem_key, request_hash, status, created_at) VALUES (?, 'k-1', ?, 'IN_PROGRESS', ?)";
        String hash = "a".repeat(64);
        jdbc.update(sql, memberId, hash, LocalDateTime.now());

        assertThatThrownBy(() -> jdbc.update(sql, memberId, hash, LocalDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
