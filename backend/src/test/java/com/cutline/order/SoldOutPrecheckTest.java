package com.cutline.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cutline.support.DatabaseCleaner;
import com.cutline.support.Fixtures;
import com.cutline.support.IntegrationTest;
import com.cutline.support.MutableClock;
import com.cutline.support.TestClockConfig;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 품절 선판정 (ADR-008): 품절된 사람은 재고 행 잠금을 기다리지 않는다.
 */
@IntegrationTest
class SoldOutPrecheckTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    MutableClock clock;

    @Autowired
    OrderExpiryService expiry;

    Fixtures fx;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        clock.set(TestClockConfig.BASE_TIME);
        fx = new Fixtures(jdbc, TestClockConfig.BASE_TIME);
    }

    @Test
    void 재고_행이_잠겨_있어도_품절이면_기다리지_않고_SOLD_OUT() throws Exception {
        long dropId = fx.openDrop(219_000, 1, 1);
        order(fx.member(), dropId, "key-1").andExpect(status().isCreated());
        long late = fx.member();

        // 다른 트랜잭션이 재고 행의 배타 잠금을 쥐고 있는 상황 (드롭 오픈 중 주문 트랜잭션들이 이 행을 차례로 잡는다).
        // 선판정이 없으면 이 요청은 innodb_lock_wait_timeout(기본 50초)까지 기다린다.
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM drop_event WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, dropId);
                lock.executeQuery().close();
            }
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                        order(late, dropId, "key-1")
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.code").value("SOLD_OUT")));
            } finally {
                holder.rollback();
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_drop_quota WHERE member_id = ?",
                Integer.class, late)).isZero();
    }

    @Test
    void 매진_뒤에도_성공했던_요청을_같은_키로_다시_보내면_처음_주문을_돌려준다() throws Exception {
        long dropId = fx.openDrop(219_000, 1, 1);
        long buyer = fx.member();
        String first = order(buyer, dropId, "key-1").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String retry = order(buyer, dropId, "key-1").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(retry).as("멱등 재요청은 선판정보다 먼저 처리돼 품절로 보이지 않는다").isEqualTo(first);
    }

    @Test
    void 만료로_재고가_돌아오면_선판정을_통과해_다시_살_수_있다() throws Exception {
        long dropId = fx.openDrop(219_000, 1, 1);
        order(fx.member(), dropId, "key-1").andExpect(status().isCreated());
        long next = fx.member();
        order(next, dropId, "key-1").andExpect(jsonPath("$.code").value("SOLD_OUT"));

        clock.advance(Duration.ofHours(1));
        assertThat(expiry.expireDue()).isEqualTo(1);

        order(next, dropId, "key-1").andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT remaining_qty FROM drop_event WHERE id = ?", Integer.class, dropId))
                .isZero();
    }

    private ResultActions order(long memberId, long dropId, String key) throws Exception {
        return mvc.perform(post("/api/drops/{id}/orders", dropId)
                .header("X-Member-Id", memberId)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"qty\":1}"));
    }
}
