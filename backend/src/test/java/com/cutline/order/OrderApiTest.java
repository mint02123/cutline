package com.cutline.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cutline.support.DatabaseCleaner;
import com.cutline.support.Fixtures;
import com.cutline.support.IntegrationTest;
import com.cutline.support.MutableClock;
import com.cutline.support.TestClockConfig;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class OrderApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    Fixtures fx;
    long member;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        clock.set(TestClockConfig.BASE_TIME);
        fx = new Fixtures(jdbc, TestClockConfig.BASE_TIME);
        member = fx.member();
    }

    @Test
    void 열린_드롭에_주문하면_결제_대기_주문이_생기고_재고가_줄어든다() throws Exception {
        long dropId = fx.openDrop(219_000, 10, 2);

        String location = order(member, dropId, 2, "key-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.amount").value(438_000))
                .andExpect(jsonPath("$.holdExpiresAt").value("2026-12-01T00:10:00Z"))
                .andReturn().getResponse().getHeader("Location");

        mvc.perform(get(location).header("X-Member-Id", member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qty").value(2));
        assertThat(remaining(dropId)).isEqualTo(8);
    }

    @Test
    void 같은_키로_다시_보내면_새_주문_없이_처음_응답을_돌려준다() throws Exception {
        long dropId = fx.openDrop(219_000, 10, 1);

        String first = order(member, dropId, 1, "key-1").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = order(member, dropId, 1, "key-1").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(remaining(dropId)).isEqualTo(9);
    }

    @Test
    void 같은_키를_다른_수량으로_재사용하면_422() throws Exception {
        long dropId = fx.openDrop(219_000, 10, 2);
        order(member, dropId, 1, "key-1").andExpect(status().isCreated());

        order(member, dropId, 2, "key-1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void 아직_열리지_않은_드롭은_409_DROP_NOT_OPEN() throws Exception {
        long dropId = fx.drop(fx.product(fx.seller(), 219_000), 10, 1, "SCHEDULED",
                TestClockConfig.BASE_TIME.plus(Duration.ofMinutes(5)));

        order(member, dropId, 1, "key-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DROP_NOT_OPEN"));
        assertThat(remaining(dropId)).isEqualTo(10);
    }

    @Test
    void 품절이면_409_SOLD_OUT이고_구매_한도는_소모되지_않는다() throws Exception {
        long dropId = fx.openDrop(219_000, 1, 1);
        long other = fx.member();
        order(other, dropId, 1, "key-1").andExpect(status().isCreated());

        order(member, dropId, 1, "key-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOLD_OUT"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_drop_quota WHERE member_id = ?",
                Integer.class, member)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_key WHERE member_id = ?",
                Integer.class, member)).as("실패한 요청의 멱등 키도 롤백돼 같은 키로 다시 시도할 수 있다").isZero();
    }

    @Test
    void 한도를_넘는_수량은_409_PURCHASE_LIMIT_EXCEEDED() throws Exception {
        long dropId = fx.openDrop(219_000, 10, 1);

        order(member, dropId, 2, "key-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_LIMIT_EXCEEDED"));

        order(member, dropId, 1, "key-2").andExpect(status().isCreated());
        order(member, dropId, 1, "key-3")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_LIMIT_EXCEEDED"));
        assertThat(remaining(dropId)).isEqualTo(9);
    }

    @Test
    void 회원_헤더가_없으면_401_멱등_키가_없으면_400() throws Exception {
        long dropId = fx.openDrop(219_000, 10, 1);

        mvc.perform(post("/api/drops/{id}/orders", dropId)
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"qty\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MEMBER_REQUIRED"));

        mvc.perform(post("/api/drops/{id}/orders", dropId)
                        .header("X-Member-Id", member)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"qty\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void 수량이_0이면_400_없는_드롭은_404() throws Exception {
        long dropId = fx.openDrop(219_000, 10, 1);

        order(member, dropId, 0, "key-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        order(member, 999, 1, "key-2")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DROP_NOT_FOUND"));
    }

    @Test
    void 다른_회원의_주문은_조회할_수_없다() throws Exception {
        long dropId = fx.openDrop(219_000, 10, 1);
        String location = order(member, dropId, 1, "key-1")
                .andReturn().getResponse().getHeader("Location");

        mvc.perform(get(location).header("X-Member-Id", fx.member()))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    private ResultActions order(long memberId, long dropId, int qty, String key) throws Exception {
        return mvc.perform(post("/api/drops/{id}/orders", dropId)
                .header("X-Member-Id", memberId)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"qty\":" + qty + "}"));
    }

    private int remaining(long dropId) {
        return jdbc.queryForObject("SELECT remaining_qty FROM drop_event WHERE id = ?", Integer.class, dropId);
    }
}
