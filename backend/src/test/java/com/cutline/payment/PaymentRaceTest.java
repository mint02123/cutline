package com.cutline.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.cutline.common.error.CutlineException;
import com.cutline.common.error.ErrorCode;
import com.cutline.order.OrderExpiryService;
import com.cutline.order.OrderPlacementService;
import com.cutline.payment.pg.FakePaymentGateway;
import com.cutline.payment.pg.FakePaymentGateway.Fault;
import com.cutline.support.DatabaseCleaner;
import com.cutline.support.Fixtures;
import com.cutline.support.IntegrationTest;
import com.cutline.support.MutableClock;
import com.cutline.support.TestClockConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 설계 문서 "만료와 결제 경합" + "PG 응답 유실"을 한꺼번에 섞는다.
 * 점유 만료 직전에 200명이 동시에 결제하고, PG는 일부를 거절·유실·타임아웃시키며, 그 와중에 만료 스케줄러 2대가 돈다.
 * 끝난 뒤 복구 작업과 만료를 한 번 더 돌리고, 돈과 재고와 주문 상태가 서로 맞는지 확인한다.
 */
@IntegrationTest
class PaymentRaceTest {

    static final long PRICE = 219_000;
    static final int ORDERS = 200;

    @Autowired
    PaymentService payments;

    @Autowired
    PaymentReconciler reconciler;

    @Autowired
    OrderPlacementService placement;

    @Autowired
    OrderExpiryService expiry;

    @Autowired
    FakePaymentGateway pg;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.cutline.consistency.ConsistencyChecker checker;

    @Autowired
    MutableClock clock;

    ExecutorService pool;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        pg.reset();
        clock.set(TestClockConfig.BASE_TIME);
        pool = Executors.newFixedThreadPool(34);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        pg.reset();
    }

    @Test
    void 만료_직전_결제_폭주와_PG_장애가_겹쳐도_돈_재고_주문이_서로_맞는다() throws Exception {
        Fixtures fx = new Fixtures(jdbc, TestClockConfig.BASE_TIME);
        long dropId = fx.openDrop(PRICE, ORDERS, 1);
        List<Long> members = fx.members(ORDERS);
        List<long[]> orders = new ArrayList<>(); // {memberId, orderId}
        for (int i = 0; i < ORDERS; i++) {
            orders.add(new long[] {members.get(i), placement.place(members.get(i), dropId, 1, "k-" + i).orderId()});
        }
        long failuresBefore = expiry.failureCount();

        // PG 결과: 70%는 정상, 나머지는 거절·응답 유실·타임아웃을 섞는다. 승인마다 50~150ms가 걸린다 (실제 PG 승인도 수백 ms 단위).
        List<Fault> faults = new ArrayList<>();
        for (int i = 0; i < ORDERS; i++) {
            faults.add(i < 140 ? Fault.NONE : Fault.values()[1 + i % 3]);
        }
        Collections.shuffle(faults);
        pg.script(faults.toArray(Fault[]::new));
        java.util.concurrent.atomic.AtomicInteger approvalsStarted = new java.util.concurrent.atomic.AtomicInteger();
        pg.beforeApprove(id -> {
            approvalsStarted.incrementAndGet();
            sleepMillis(50 + ThreadLocalRandom.current().nextInt(101));
        });

        Instant holdEnds = TestClockConfig.BASE_TIME.plus(Duration.ofMinutes(10));
        clock.set(holdEnds.minusMillis(1));

        Map<String, Integer> outcomes = new ConcurrentHashMap<>();
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        // 만료 스케줄러를 먼저 제출해야 스레드 풀에서 결제들과 같은 시점에 출발한다.
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                // 결제 60건이 PG에 들어간 시점에 점유 시간이 끝난다. 일부는 PG 승인 도중에 만료된다.
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (approvalsStarted.get() < 60 && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                clock.set(holdEnds); // 점유 시간이 끝나는 순간
                for (int n = 0; n < 20; n++) {
                    expiry.expireDue();
                    sleepMillis(1);
                }
                return null;
            }));
        }
        for (long[] o : orders) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    PaymentResult r = payments.confirm(o[0], o[1], "pk-" + o[1], PRICE);
                    outcomes.merge(r.orderStatus() + "/" + r.paymentStatus(), 1, Integer::sum);
                } catch (CutlineException e) {
                    outcomes.merge(e.code().name(), 1, Integer::sum);
                } catch (Throwable t) {
                    unexpected.add(t);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }

        // 경합이 끝난 뒤의 정리: 복구 작업 → 만료 → 복구 작업(보상 재시도)
        reconciler.reconcile();
        expiry.expireDue();
        reconciler.reconcile();

        System.out.println("PAYMENT RACE outcomes=" + outcomes + ", paid=" + countWhere("o.status = 'PAID'")
                + ", refunded=" + countWhere("p.status = 'CANCELED'") + ", pgCancelCalls=" + pg.cancelCalls());

        assertThat(unexpected).as("500으로 나갈 예외").isEmpty();
        assertThat(expiry.failureCount() - failuresBefore).as("만료 중 예외").isZero();
        assertThat(outcomes.keySet()).allMatch(k -> k.equals("PAID/APPROVED") || k.equals("PENDING_PAYMENT/UNKNOWN")
                || k.equals(ErrorCode.HOLD_EXPIRED.name()) || k.equals(ErrorCode.PAYMENT_DECLINED.name()));

        // 1. 결제 완료 ⇔ 승인된 결제. 승인됐는데 주문이 PAID가 아닌 결제(환불 누락)는 없다.
        assertThat(countWhere("o.status = 'PAID' AND (p.status IS NULL OR p.status <> 'APPROVED')")).isZero();
        assertThat(countWhere("p.status = 'APPROVED' AND o.status <> 'PAID'")).isZero();
        // 2. 결과를 모르는 결제가 남지 않는다.
        assertThat(countWhere("p.status IN ('UNKNOWN', 'REQUESTED')")).isZero();
        // 3. PG에서 실제로 빠져나간 돈 = PAID 주문. 같은 주문에 두 번 승인된 적이 없다.
        int paid = countWhere("o.status = 'PAID'");
        assertThat(pg.activeCharges()).isEqualTo(paid);
        assertThat(orders).allMatch(o -> pg.chargeCount(o[1]) <= 1);
        // 4. 점유 중인 주문은 없고, 남은 재고 + 팔린 수량 = 총수량.
        assertThat(countWhere("o.status = 'PENDING_PAYMENT'")).isZero();
        int remaining = jdbc.queryForObject("SELECT remaining_qty FROM drop_event WHERE id = ?", Integer.class, dropId);
        assertThat(remaining + paid).isEqualTo(ORDERS);
        assertThat(checker.check()).as("정합성 체커").isEmpty();
    }

    private int countWhere(String condition) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders o LEFT JOIN payment p ON p.order_id = o.id WHERE " + condition,
                Integer.class);
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
