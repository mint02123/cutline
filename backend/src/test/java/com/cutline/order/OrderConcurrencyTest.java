package com.cutline.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.cutline.common.error.CutlineException;
import com.cutline.common.error.ErrorCode;
import com.cutline.support.DatabaseCleaner;
import com.cutline.support.Fixtures;
import com.cutline.support.IntegrationTest;
import com.cutline.support.MutableClock;
import com.cutline.support.TestClockConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 설계 문서 "정합성 테스트" 1~3번. 서비스를 여러 스레드에서 동시에 호출하고, 끝난 뒤 DB 상태로 판정한다.
 * 예상하지 못한 예외(데드락, 제약 위반 등)는 하나라도 나오면 실패다.
 */
@IntegrationTest
class OrderConcurrencyTest {

    static final int THREADS = 32;

    @Autowired
    OrderPlacementService placement;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    Fixtures fx;
    ExecutorService pool;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        clock.set(TestClockConfig.BASE_TIME);
        fx = new Fixtures(jdbc, TestClockConfig.BASE_TIME);
        pool = Executors.newFixedThreadPool(THREADS);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void 재고_100개에_1000명이_동시에_몰려도_정확히_100건만_주문된다() throws Exception {
        long dropId = fx.openDrop(219_000, 100, 1);
        List<Long> members = fx.members(1000);

        Outcome outcome = race(1000, i -> placement.place(members.get(i), dropId, 1, "k-" + i));

        assertThat(outcome.unexpected).isEmpty();
        assertThat(outcome.successes.get()).isEqualTo(100);
        assertThat(outcome.failures).containsOnlyKeys(ErrorCode.SOLD_OUT);
        assertThat(remaining(dropId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM orders WHERE drop_id = ?", dropId)).isEqualTo(100);
        assertThat(count("SELECT COALESCE(SUM(qty), 0) FROM orders WHERE drop_id = ?", dropId)).isEqualTo(100);
        assertThat(count("SELECT COALESCE(SUM(used_qty), 0) FROM member_drop_quota WHERE drop_id = ?", dropId))
                .as("품절로 실패한 900명의 한도는 롤백돼야 한다").isEqualTo(100);
    }

    @Test
    void 한_사람이_다른_키로_50번_동시에_요청해도_한도_1개만_산다() throws Exception {
        long dropId = fx.openDrop(219_000, 100, 1);
        long member = fx.member();

        Outcome outcome = race(50, i -> placement.place(member, dropId, 1, "k-" + i));

        assertThat(outcome.unexpected).isEmpty();
        assertThat(outcome.successes.get()).isEqualTo(1);
        assertThat(outcome.failures).containsOnlyKeys(ErrorCode.PURCHASE_LIMIT_EXCEEDED);
        assertThat(remaining(dropId)).isEqualTo(99);
        assertThat(count("SELECT used_qty FROM member_drop_quota WHERE drop_id = ? AND member_id = " + member, dropId))
                .isEqualTo(1);
    }

    @Test
    void 한도_2개인_드롭에_1개씩_동시에_요청하면_정확히_2개까지만_산다() throws Exception {
        long dropId = fx.openDrop(219_000, 100, 2);
        long member = fx.member();

        Outcome outcome = race(30, i -> placement.place(member, dropId, 1, "k-" + i));

        assertThat(outcome.unexpected).isEmpty();
        assertThat(outcome.successes.get()).isEqualTo(2);
        assertThat(remaining(dropId)).isEqualTo(98);
    }

    @Test
    void 같은_멱등_키로_50번_동시에_보내도_주문은_하나고_모두_같은_주문을_받는다() throws Exception {
        long dropId = fx.openDrop(219_000, 100, 1);
        long member = fx.member();

        Outcome outcome = race(50, i -> placement.place(member, dropId, 1, "same-key"));

        assertThat(outcome.unexpected).isEmpty();
        assertThat(outcome.failures).as("앞 요청이 끝날 때까지 PK 잠금에서 기다렸다가 저장된 응답을 받는다").isEmpty();
        assertThat(outcome.receipts).hasSize(50);
        assertThat(outcome.receipts.stream().map(OrderReceipt::orderId).distinct()).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM orders WHERE drop_id = ?", dropId)).isEqualTo(1);
        assertThat(remaining(dropId)).isEqualTo(99);
    }

    private Outcome race(int requests, IntFunction<OrderReceipt> call) throws Exception {
        Outcome outcome = new Outcome();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    outcome.receipts.add(call.apply(n));
                    outcome.successes.incrementAndGet();
                } catch (CutlineException e) {
                    outcome.failures.merge(e.code(), 1, Integer::sum);
                } catch (Exception e) {
                    outcome.unexpected.add(e);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        return outcome;
    }

    private int remaining(long dropId) {
        return count("SELECT remaining_qty FROM drop_event WHERE id = ?", dropId);
    }

    private int count(String sql, long arg) {
        return jdbc.queryForObject(sql, Integer.class, arg);
    }

    static final class Outcome {
        final AtomicInteger successes = new AtomicInteger();
        final Map<ErrorCode, Integer> failures = new ConcurrentHashMap<>();
        final List<Exception> unexpected = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<OrderReceipt> receipts = java.util.Collections.synchronizedList(new ArrayList<>());
    }
}
