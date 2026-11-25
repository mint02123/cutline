package com.cutline.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.cutline.consistency.ConsistencyChecker;
import com.cutline.support.DatabaseCleaner;
import com.cutline.support.Fixtures;
import com.cutline.support.IntegrationTest;
import com.cutline.support.MutableClock;
import com.cutline.support.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 관문 토큰 재동기화 (ADR-009). 더 팔 수 있는 쪽(토큰 > 재고)은 바로, 덜 파는 쪽은 두 번 연속 확인된 뒤에 맞춘다.
 */
@IntegrationTest
class GateResyncTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    StockGate gate;

    @Autowired
    GateResync resync;

    @Autowired
    ConsistencyChecker checker;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    OrderAdmissionService admission;

    Fixtures fx;
    long dropId;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        clock.set(TestClockConfig.BASE_TIME);
        fx = new Fixtures(jdbc, TestClockConfig.BASE_TIME);
        dropId = fx.openDrop(219_000, 10, 1);
        admission.place(fx.member(), dropId, 1, "key-1");
        resync.resync(); // 앞 테스트가 남긴 관찰 기록을 비운다
        assertThat(gate.tokens(dropId)).isEqualTo(9);
    }

    @Test
    void 토큰이_MySQL보다_많으면_체커가_잡고_재동기화가_바로_낮춘다() {
        setTokens(12);

        assertThat(checker.check()).extracting(ConsistencyChecker.Violation::rule)
                .containsExactly("gate_tokens_within_stock");

        assertThat(resync.resync().lowered()).isEqualTo(1);
        assertThat(gate.tokens(dropId)).isEqualTo(9);
        assertThat(checker.check()).isEmpty();
    }

    @Test
    void 토큰이_모자라도_한_번_본_것으로는_올리지_않고_두_번_연속이면_올린다() {
        setTokens(6); // 반환하지 못한 토큰 3개

        assertThat(resync.resync()).isEqualTo(new GateResync.Report(0, 0, 1));
        assertThat(gate.tokens(dropId)).as("진행 중인 주문일 수 있으므로 기다린다").isEqualTo(6);
        assertThat(checker.check()).as("덜 파는 쪽은 위반이 아니다").isEmpty();

        assertThat(resync.resync()).isEqualTo(new GateResync.Report(0, 1, 0));
        assertThat(gate.tokens(dropId)).isEqualTo(9);
    }

    @Test
    void 그_사이_주문이_진행되면_올리지_않는다() {
        setTokens(6);
        resync.resync();

        // 관문 통과(토큰 6 → 5)와 MySQL 차감(9 → 8)이 그 사이에 일어났다
        admission.place(fx.member(), dropId, 1, "key-1");

        assertThat(resync.resync().raised()).isZero();
        assertThat(gate.tokens(dropId)).isEqualTo(5);
        assertThat(resync.resync().raised()).as("판매가 멈춘 뒤 두 번째 확인에서 맞춘다").isEqualTo(1);
        assertThat(gate.tokens(dropId)).isEqualTo(8);
    }

    @Test
    void 관문_키가_없는_드롭은_건드리지_않는다() {
        redis.delete("gate:{" + dropId + "}");

        assertThat(resync.resync()).isEqualTo(new GateResync.Report(0, 0, 0));
        assertThat(gate.tokens(dropId)).isNull();
    }

    private void setTokens(int tokens) {
        redis.opsForHash().put("gate:{" + dropId + "}", "stock", String.valueOf(tokens));
    }
}
