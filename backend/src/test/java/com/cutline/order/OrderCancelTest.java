package com.cutline.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cutline.consistency.ConsistencyChecker;
import com.cutline.payment.PaymentReconciler;
import com.cutline.payment.PaymentService;
import com.cutline.payment.pg.FakePaymentGateway;
import com.cutline.support.DatabaseCleaner;
import com.cutline.support.Fixtures;
import com.cutline.support.IntegrationTest;
import com.cutline.support.MutableClock;
import com.cutline.support.TestClockConfig;
import com.cutline.common.error.CutlineException;
import com.cutline.common.error.ErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class OrderCancelTest {

    static final long PRICE = 219_000;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    OrderPlacementService placement;

    @Autowired
    OrderCancelService cancels;

    @Autowired
    OrderExpiryService expiry;

    @Autowired
    PaymentService payments;

    @Autowired
    PaymentReconciler reconciler;

    @Autowired
    FakePaymentGateway pg;

    @Autowired
    ConsistencyChecker checker;

    Fixtures fx;
    long dropId;
    long member;
    long orderId;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        pg.reset();
        clock.set(TestClockConfig.BASE_TIME);
        fx = new Fixtures(jdbc, TestClockConfig.BASE_TIME);
        dropId = fx.openDrop(PRICE, 10, 1);
        member = fx.member();
        orderId = placement.place(member, dropId, 1, "k-1").orderId();
    }

    @AfterEach
    void tearDown() {
        pg.reset();
    }

    @Test
    void 결제_전_취소는_재고와_한도를_돌려주고_다시_보내도_같은_결과() throws Exception {
        cancel(orderId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELED"));
        cancel(orderId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELED"));

        assertThat(remaining()).isEqualTo(10);
        assertThat(placement.place(member, dropId, 1, "k-2").qty()).as("돌려받은 한도로 재구매").isEqualTo(1);
        assertThat(checker.check()).isEmpty();
    }

    @Test
    void 결제_후_취소는_REFUNDED가_되고_PG에서도_환불된다() throws Exception {
        payments.confirm(member, orderId, "pk-1", PRICE);

        cancel(orderId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED"));

        assertThat(pg.isCanceled(orderId)).isTrue();
        assertThat(paymentStatus()).isEqualTo("CANCELED");
        assertThat(remaining()).isEqualTo(10);
        assertThat(checker.check()).isEmpty();
    }

    @Test
    void 환불_요청이_실패해도_체커가_잡고_복구_작업이_다시_환불한다() throws Exception {
        payments.confirm(member, orderId, "pk-1", PRICE);
        pg.failCancels(true);

        cancel(orderId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED"));
        assertThat(paymentStatus()).isEqualTo("APPROVED");
        assertThat(checker.check()).extracting(ConsistencyChecker.Violation::rule)
                .containsExactly("approved_payment_has_paid_order");

        pg.failCancels(false);
        assertThat(reconciler.reconcile().refunded()).isEqualTo(1);
        assertThat(pg.isCanceled(orderId)).isTrue();
        assertThat(checker.check()).isEmpty();
    }

    @Test
    void PG_승인_도중_취소되면_승인을_되돌린다() {
        pg.beforeApprove(id -> cancels.cancel(member, orderId));

        CutlineException e = org.junit.jupiter.api.Assertions.assertThrows(CutlineException.class,
                () -> payments.confirm(member, orderId, "pk-1", PRICE));

        assertThat(e.code()).isEqualTo(ErrorCode.HOLD_EXPIRED);
        assertThat(pg.isCanceled(orderId)).isTrue();
        assertThat(paymentStatus()).isEqualTo("CANCELED");
        assertThat(remaining()).isEqualTo(10);
        assertThat(checker.check()).isEmpty();
    }

    @Test
    void 만료된_주문은_취소할_수_없고_남의_주문은_보이지_않는다() throws Exception {
        clock.advance(Duration.ofMinutes(10));
        expiry.expireDue();

        cancel(orderId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_CANCELABLE"));
        mvc.perform(post("/api/orders/{id}/cancel", orderId).header("X-Member-Id", fx.member()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 취소와_만료가_동시에_와도_복구는_한_번씩만_일어난다() throws Exception {
        long bigDrop = fx.openDrop(PRICE, 100, 1);
        List<Long> members = fx.members(100);
        List<Long> orders = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            orders.add(placement.place(members.get(i), bigDrop, 1, "k-" + i).orderId());
        }
        clock.advance(Duration.ofMinutes(10));
        long failuresBefore = expiry.failureCount();

        List<Callable<Object>> tasks = new ArrayList<>();
        tasks.add(() -> expiry.expireDue());
        tasks.add(() -> expiry.expireDue());
        for (int i = 0; i < 100; i++) {
            long m = members.get(i);
            long o = orders.get(i);
            tasks.add(() -> {
                try {
                    return cancels.cancel(m, o);
                } catch (CutlineException e) {
                    return e.code(); // 만료가 먼저면 ORDER_NOT_CANCELABLE
                }
            });
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results.subList(2, results.size()))
                .allMatch(r -> r == OrderStatus.CANCELED || r == ErrorCode.ORDER_NOT_CANCELABLE);
        assertThat(expiry.failureCount() - failuresBefore).isZero();
        assertThat(jdbc.queryForObject("SELECT remaining_qty FROM drop_event WHERE id = ?", Integer.class, bigDrop))
                .isEqualTo(100);
        assertThat(checker.check()).isEmpty();
    }

    private List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private ResultActions cancel(long id) throws Exception {
        return mvc.perform(post("/api/orders/{id}/cancel", id).header("X-Member-Id", member));
    }

    private int remaining() {
        return jdbc.queryForObject("SELECT remaining_qty FROM drop_event WHERE id = ?", Integer.class, dropId);
    }

    private String paymentStatus() {
        return jdbc.queryForObject("SELECT status FROM payment WHERE order_id = ?", String.class, orderId);
    }
}
