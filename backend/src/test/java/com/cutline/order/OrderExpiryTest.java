package com.cutline.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.cutline.common.error.CutlineException;
import com.cutline.common.error.ErrorCode;
import com.cutline.support.DatabaseCleaner;
import com.cutline.support.Fixtures;
import com.cutline.support.IntegrationTest;
import com.cutline.support.MutableClock;
import com.cutline.support.TestClockConfig;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 설계 문서 "정합성 테스트"의 다중 인스턴스 만료와, 만료·신규 주문 동시 진행. */
@IntegrationTest
class OrderExpiryTest {

    static final Duration HOLD = Duration.ofMinutes(10);

    @Autowired
    OrderPlacementService placement;

    @Autowired
    OrderExpiryService expiry;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    Fixtures fx;
    ExecutorService pool;
    long failuresBefore;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        clock.set(TestClockConfig.BASE_TIME);
        fx = new Fixtures(jdbc, TestClockConfig.BASE_TIME);
        pool = Executors.newFixedThreadPool(32);
        failuresBefore = expiry.failureCount();
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void 결제_대기_시간이_지나면_만료되고_재고와_한도가_돌아온다() {
        long dropId = fx.openDrop(219_000, 10, 2);
        long member = fx.member();
        OrderReceipt receipt = placement.place(member, dropId, 2, "k-1");

        clock.advance(HOLD.minusSeconds(1));
        assertThat(expiry.expireDue()).as("만료 1초 전").isZero();

        clock.advance(Duration.ofSeconds(1));
        assertThat(expiry.expireDue()).isEqualTo(1);
        assertThat(expiry.expireDue()).as("이미 만료된 주문은 다시 처리하지 않는다").isZero();

        assertThat(status(receipt.orderId())).isEqualTo("EXPIRED");
        assertThat(remaining(dropId)).isEqualTo(10);
        assertThat(usedQuota(dropId, member)).isZero();
        assertThat(placement.place(member, dropId, 2, "k-2").qty())
                .as("돌려받은 한도로 다시 살 수 있다").isEqualTo(2);
        assertNoFailures();
    }

    @Test
    void 결제_결과가_UNKNOWN인_주문은_만료시키지_않는다() {
        long dropId = fx.openDrop(219_000, 10, 1);
        long unknownOrder = placement.place(fx.member(), dropId, 1, "k-1").orderId();
        long plainOrder = placement.place(fx.member(), dropId, 1, "k-1").orderId();
        insertPayment(unknownOrder, "UNKNOWN");

        clock.advance(HOLD);
        assertThat(expiry.expireDue()).isEqualTo(1);

        assertThat(status(unknownOrder)).as("돈이 나갔을 수 있으므로 점유를 유지").isEqualTo("PENDING_PAYMENT");
        assertThat(status(plainOrder)).isEqualTo("EXPIRED");
        assertThat(remaining(dropId)).isEqualTo(9);
    }

    @Test
    void 스케줄러_4대가_같은_주문들을_동시에_만료해도_복구는_한_번씩만_일어난다() throws Exception {
        long dropId = fx.openDrop(219_000, 300, 1);
        List<Long> members = fx.members(300);
        for (int i = 0; i < 300; i++) {
            placement.place(members.get(i), dropId, 1, "k-" + i);
        }
        assertThat(remaining(dropId)).isZero();

        clock.advance(HOLD);
        List<Integer> perInstance = runConcurrently(4, expiry::expireDue);

        assertThat(perInstance.stream().mapToInt(Integer::intValue).sum())
                .as("네 대가 처리한 건수의 합이 정확히 주문 수와 같다").isEqualTo(300);
        assertThat(count("SELECT COUNT(*) FROM orders WHERE status = 'EXPIRED' AND drop_id = ?", dropId))
                .isEqualTo(300);
        assertThat(remaining(dropId)).as("재고가 두 번 복구되면 CHECK(remaining <= total)에 걸린다").isEqualTo(300);
        assertThat(count("SELECT COALESCE(SUM(used_qty), 0) FROM member_drop_quota WHERE drop_id = ?", dropId))
                .isZero();
        assertNoFailures();
    }

    @Test
    void 만료와_신규_주문이_동시에_진행돼도_재고_합계가_맞고_데드락이_없다() throws Exception {
        long dropId = fx.openDrop(219_000, 100, 1);
        List<Long> first = fx.members(100);
        for (int i = 0; i < 100; i++) {
            placement.place(first.get(i), dropId, 1, "k-" + i);
        }
        List<Long> second = fx.members(300);
        clock.advance(HOLD);

        // 만료 스케줄러 2대와 새 구매자 300명이 동시에 출발한다.
        Map<ErrorCode, Integer> rejected = new ConcurrentHashMap<>();
        List<Exception> unexpected = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger bought = new AtomicInteger();
        List<Callable<Integer>> tasks = new ArrayList<>();
        tasks.add(expiry::expireDue);
        tasks.add(expiry::expireDue);
        for (int i = 0; i < 300; i++) {
            long member = second.get(i);
            tasks.add(() -> {
                try {
                    placement.place(member, dropId, 1, "k-" + member);
                    bought.incrementAndGet();
                } catch (CutlineException e) {
                    rejected.merge(e.code(), 1, Integer::sum);
                } catch (Exception e) {
                    unexpected.add(e);
                }
                return 0;
            });
        }
        runConcurrently(tasks);
        expiry.expireDue(); // 출발 시점이 늦어 놓친 주문까지 정리

        assertThat(unexpected).isEmpty();
        assertNoFailures();
        assertThat(count("SELECT COUNT(*) FROM orders WHERE status = 'EXPIRED' AND drop_id = ?", dropId))
                .isEqualTo(100);
        assertThat(bought.get()).isBetween(0, 100);
        assertThat(rejected).containsOnlyKeys(ErrorCode.SOLD_OUT);
        assertThat(remaining(dropId) + count(
                "SELECT COALESCE(SUM(qty), 0) FROM orders WHERE status = 'PENDING_PAYMENT' AND drop_id = ?", dropId))
                .as("남은 재고 + 점유 중인 수량 = 총수량").isEqualTo(100);
        assertThat(count("SELECT COALESCE(SUM(used_qty), 0) FROM member_drop_quota WHERE drop_id = ?", dropId))
                .isEqualTo(bought.get());
    }

    private List<Integer> runConcurrently(int copies, Callable<Integer> task) throws Exception {
        return runConcurrently(Collections.nCopies(copies, task));
    }

    private List<Integer> runConcurrently(List<Callable<Integer>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<Integer> results = new ArrayList<>();
        for (Future<Integer> f : futures) {
            results.add(f.get());
        }
        return results;
    }

    private void assertNoFailures() {
        assertThat(expiry.failureCount() - failuresBefore).as("만료 중 예외(데드락 등)").isZero();
    }

    private void insertPayment(long orderId, String status) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                INSERT INTO payment (order_id, amount, status, created_at, updated_at)
                SELECT id, amount, ?, ?, ? FROM orders WHERE id = ?""", status, now, now, orderId);
    }

    private String status(long orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private int remaining(long dropId) {
        return count("SELECT remaining_qty FROM drop_event WHERE id = ?", dropId);
    }

    private int usedQuota(long dropId, long member) {
        return jdbc.queryForObject("SELECT used_qty FROM member_drop_quota WHERE drop_id = ? AND member_id = ?",
                Integer.class, dropId, member);
    }

    private int count(String sql, long arg) {
        return jdbc.queryForObject(sql, Integer.class, arg);
    }
}
