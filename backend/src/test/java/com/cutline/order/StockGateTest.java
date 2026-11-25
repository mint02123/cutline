package com.cutline.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cutline.common.error.CutlineException;
import com.cutline.common.error.ErrorCode;
import com.cutline.consistency.ConsistencyChecker;
import com.cutline.support.DatabaseCleaner;
import com.cutline.support.Fixtures;
import com.cutline.support.IntegrationTest;
import com.cutline.support.MutableClock;
import com.cutline.support.TestClockConfig;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Redis 1차 관문 (ADR-009). 관문은 거르기만 하고 판정은 MySQL이 한다.
 * Redis와 MySQL이 어긋나면 덜 파는 쪽으로만 어긋나야 한다.
 */
@IntegrationTest
class StockGateTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    OrderAdmissionService admission;

    @Autowired
    StockGate gate;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    ConsistencyChecker checker;

    Fixtures fx;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        clock.set(TestClockConfig.BASE_TIME);
        fx = new Fixtures(jdbc, TestClockConfig.BASE_TIME);
    }

    @Test
    void 구매자_1000명이_재고_100개에_몰리면_정확히_100건만_팔리고_토큰과_재고가_함께_0이_된다() throws Exception {
        long dropId = fx.openDrop(219_000, 100, 1);
        List<Long> buyers = fx.members(1_000);
        Map<ErrorCode, AtomicInteger> failures = new ConcurrentHashMap<>();
        AtomicInteger created = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch start = new CountDownLatch(1);
        for (Long buyer : buyers) {
            pool.submit(() -> {
                start.await();
                try {
                    admission.place(buyer, dropId, 1, "key-" + buyer);
                    created.incrementAndGet();
                } catch (CutlineException e) {
                    failures.computeIfAbsent(e.code(), k -> new AtomicInteger()).incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(created).hasValue(100);
        assertThat(failures).containsOnlyKeys(ErrorCode.SOLD_OUT);
        assertThat(remaining(dropId)).isZero();
        assertThat(gate.tokens(dropId)).isZero();
        assertThat(checker.check()).isEmpty();
    }

    @Test
    void 토큰이_0이면_MySQL에_재고가_남아_있어도_품절이다_덜_파는_쪽으로만_틀린다() throws Exception {
        long dropId = fx.openDrop(219_000, 1, 1);
        order(fx.member(), dropId, "key-1").andExpect(status().isCreated());
        // Redis 토큰은 0인데 MySQL 재고는 남은 상태를 만든다 (예: 토큰 반환 전에 서버가 죽은 경우).
        jdbc.update("UPDATE drop_event SET remaining_qty = 5, total_qty = 6 WHERE id = ?", dropId);

        order(fx.member(), dropId, "key-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOLD_OUT"));
        assertThat(remaining(dropId)).as("관문이 막아 MySQL 재고는 건드리지 않았다").isEqualTo(5);
    }

    @Test
    void 관문에서_한도를_넘으면_토큰을_쓰지_않고_PURCHASE_LIMIT_EXCEEDED() throws Exception {
        long dropId = fx.openDrop(219_000, 10, 1);
        long buyer = fx.member();
        order(buyer, dropId, "key-1").andExpect(status().isCreated());

        order(buyer, dropId, "key-2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_LIMIT_EXCEEDED"));
        assertThat(gate.tokens(dropId)).isEqualTo(9);
    }

    @Test
    void 관문을_통과했는데_MySQL에서_막히면_토큰을_돌려준다() throws Exception {
        long dropId = fx.openDrop(219_000, 10, 1);
        long buyer = fx.member();
        order(buyer, dropId, "key-1").andExpect(status().isCreated());
        // Redis 재시작 등으로 회원별 수량을 잃은 상황. 관문은 통과시키지만 MySQL 한도 upsert가 막아야 한다.
        redis.delete("gate:{" + dropId + "}:bought");

        order(buyer, dropId, "key-2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_LIMIT_EXCEEDED"));
        assertThat(gate.tokens(dropId)).as("MySQL에서 실패한 요청의 토큰은 반환된다").isEqualTo(9);
        assertThat(remaining(dropId)).isEqualTo(9);
    }

    @Test
    void 매진_뒤_멱등_재요청은_관문을_거치지_않고_처음_주문을_돌려준다() throws Exception {
        long dropId = fx.openDrop(219_000, 1, 1);
        long buyer = fx.member();
        String first = order(buyer, dropId, "key-1").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String retry = order(buyer, dropId, "key-1").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(retry).isEqualTo(first);
        assertThat(gate.tokens(dropId)).isZero();
    }

    @Test
    void 키가_없으면_MySQL_남은_재고로_적재하고_열리지_않은_드롭은_적재하지_않는다() throws Exception {
        long open = fx.openDrop(219_000, 10, 2);
        jdbc.update("UPDATE drop_event SET remaining_qty = 7 WHERE id = ?", open);
        long scheduled = fx.drop(fx.product(fx.seller(), 219_000), 10, 1, "SCHEDULED",
                TestClockConfig.BASE_TIME.plusSeconds(60));

        order(fx.member(), open, "key-1").andExpect(status().isCreated());
        order(fx.member(), scheduled, "key-1").andExpect(jsonPath("$.code").value("DROP_NOT_OPEN"));

        assertThat(gate.tokens(open)).as("7개로 적재된 뒤 1개 통과").isEqualTo(6);
        assertThat(gate.tokens(scheduled)).isNull();
    }

    @Test
    void 만료로_MySQL_재고가_돌아오면_커밋_뒤_토큰도_돌아온다(@Autowired OrderExpiryService expiry) throws Exception {
        long dropId = fx.openDrop(219_000, 3, 1);
        order(fx.member(), dropId, "key-1").andExpect(status().isCreated());
        assertThat(gate.tokens(dropId)).isEqualTo(2);

        clock.advance(java.time.Duration.ofHours(1));
        assertThat(expiry.expireDue()).isEqualTo(1);

        assertThat(remaining(dropId)).isEqualTo(3);
        assertThat(gate.tokens(dropId)).isEqualTo(3);
    }

    private ResultActions order(long memberId, long dropId, String key) throws Exception {
        return mvc.perform(post("/api/drops/{id}/orders", dropId)
                .header("X-Member-Id", memberId)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"qty\":1}"));
    }

    private int remaining(long dropId) {
        return jdbc.queryForObject("SELECT remaining_qty FROM drop_event WHERE id = ?", Integer.class, dropId);
    }
}
