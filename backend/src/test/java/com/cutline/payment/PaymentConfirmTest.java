package com.cutline.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cutline.order.OrderExpiryService;
import com.cutline.order.OrderPlacementService;
import com.cutline.payment.pg.FakePaymentGateway;
import com.cutline.payment.pg.FakePaymentGateway.Fault;
import com.cutline.support.DatabaseCleaner;
import com.cutline.support.Fixtures;
import com.cutline.support.IntegrationTest;
import com.cutline.support.MutableClock;
import com.cutline.support.TestClockConfig;
import java.sql.Timestamp;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 결제 확정의 정상·거절·응답 유실·보상 시나리오를 하나씩 순서대로 재현한다. */
@IntegrationTest
class PaymentConfirmTest {

    static final long PRICE = 219_000;
    static final Duration HOLD = Duration.ofMinutes(10);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    FakePaymentGateway pg;

    @Autowired
    OrderPlacementService placement;

    @Autowired
    OrderExpiryService expiry;

    @Autowired
    PaymentReconciler reconciler;

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

    @Test
    void 승인되면_주문은_PAID_결제는_APPROVED() throws Exception {
        confirm(orderId, PRICE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("PAID"))
                .andExpect(jsonPath("$.paymentStatus").value("APPROVED"));

        assertState("PAID", "APPROVED");
        assertThat(pg.chargeCount(orderId)).isEqualTo(1);
    }

    @Test
    void 이미_결제된_주문에_다시_보내도_PG를_다시_부르지_않는다() throws Exception {
        confirm(orderId, PRICE).andExpect(status().isOk());
        pg.script(Fault.DECLINE); // 다시 불렸다면 거절이 나올 것

        confirm(orderId, PRICE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("PAID"));
        assertThat(pg.chargeCount(orderId)).isEqualTo(1);
    }

    @Test
    void 금액이_다르면_PG를_부르기_전에_거절한다() throws Exception {
        confirm(orderId, PRICE - 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_AMOUNT_MISMATCH"));

        assertThat(pg.chargeCount(orderId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM payment WHERE order_id = ?")).isZero();
    }

    @Test
    void 거절되면_결제는_FAILED이고_점유_시간_안에_다시_시도할_수_있다() throws Exception {
        pg.script(Fault.DECLINE);
        confirm(orderId, PRICE)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PAYMENT_DECLINED"));
        assertState("PENDING_PAYMENT", "FAILED");

        confirm(orderId, PRICE, "pk-retry").andExpect(status().isOk());
        assertState("PAID", "APPROVED");
        assertThat(jdbc.queryForObject("SELECT pg_payment_key FROM payment WHERE order_id = ?", String.class, orderId))
                .isEqualTo("pk-retry");
    }

    @Test
    void 점유_시간이_지난_주문은_PG를_부르지_않고_409() throws Exception {
        clock.advance(HOLD);

        confirm(orderId, PRICE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
        assertThat(pg.chargeCount(orderId)).isZero();
    }

    @Test
    void 남의_주문은_결제할_수_없다() throws Exception {
        mvc.perform(post("/api/orders/{id}/payments", orderId)
                        .header("X-Member-Id", fx.member())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("pk-1", PRICE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void 승인_응답이_사라지면_202_UNKNOWN이고_만료되지_않으며_복구_작업이_PAID로_확정한다() throws Exception {
        pg.script(Fault.LOSE_RESPONSE);
        confirm(orderId, PRICE)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.paymentStatus").value("UNKNOWN"));
        assertState("PENDING_PAYMENT", "UNKNOWN");

        clock.advance(HOLD.plusMinutes(5));
        assertThat(expiry.expireDue()).as("돈이 나갔을 수 있으므로 만료하지 않는다").isZero();

        assertThat(reconciler.reconcile().resolved()).isEqualTo(1);
        assertState("PAID", "APPROVED");
        assertThat(pg.chargeCount(orderId)).as("중복 승인 없음").isEqualTo(1);
        assertThat(remaining()).isEqualTo(9);
    }

    @Test
    void 요청이_PG에_닿지_않았으면_복구_작업이_FAILED로_정리하고_만료가_재고를_돌려준다() throws Exception {
        pg.script(Fault.TIMEOUT);
        confirm(orderId, PRICE).andExpect(status().isAccepted());

        reconciler.reconcile();
        assertState("PENDING_PAYMENT", "FAILED");

        clock.advance(HOLD);
        assertThat(expiry.expireDue()).isEqualTo(1);
        assertState("EXPIRED", "FAILED");
        assertThat(remaining()).isEqualTo(10);
        assertThat(pg.chargeCount(orderId)).isZero();
    }

    @Test
    void PG_조회도_실패하면_UNKNOWN을_유지하고_다음_실행에서_다시_시도한다() throws Exception {
        pg.script(Fault.LOSE_RESPONSE);
        confirm(orderId, PRICE).andExpect(status().isAccepted());

        pg.failInquiries(true);
        assertThat(reconciler.reconcile().stillUnknown()).isEqualTo(1);
        assertState("PENDING_PAYMENT", "UNKNOWN");

        pg.failInquiries(false);
        reconciler.reconcile();
        assertState("PAID", "APPROVED");
    }

    @Test
    void PG_승인_도중_만료되면_승인을_취소하고_409를_돌려준다() throws Exception {
        // PG가 승인 처리를 하는 사이에 점유 시간이 지나고 만료 스케줄러가 돈다.
        pg.beforeApprove(id -> {
            clock.advance(HOLD);
            assertThat(expiry.expireDue()).isEqualTo(1);
        });

        confirm(orderId, PRICE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));

        assertState("EXPIRED", "CANCELED");
        assertThat(pg.isCanceled(orderId)).as("PG 쪽에서도 취소(환불)됐다").isTrue();
        assertThat(remaining()).as("재고는 만료로 한 번만 복구").isEqualTo(10);
    }

    @Test
    void 보상_취소가_실패해도_복구_작업이_다시_취소한다() throws Exception {
        pg.beforeApprove(id -> {
            clock.advance(HOLD);
            expiry.expireDue();
        });
        pg.failCancels(true);
        confirm(orderId, PRICE).andExpect(status().isConflict());
        assertState("EXPIRED", "APPROVED");

        pg.failCancels(false);
        assertThat(reconciler.reconcile().refunded()).isEqualTo(1);
        assertState("EXPIRED", "CANCELED");
        assertThat(pg.isCanceled(orderId)).isTrue();
    }

    @Test
    void 서버가_PG_호출_도중_죽어_REQUESTED로_남은_결제도_복구한다() {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                INSERT INTO payment (order_id, pg_payment_key, amount, status, created_at, updated_at)
                VALUES (?, 'pk-crash', ?, 'REQUESTED', ?, ?)""", orderId, PRICE, now, now);
        pg.approve("pk-crash", orderId, PRICE); // PG는 승인했지만 우리 서버는 그 전에 죽었다

        assertThat(reconciler.reconcile().resolved()).as("아직 오래되지 않은 REQUESTED는 진행 중일 수 있다").isZero();

        clock.advance(Duration.ofSeconds(30));
        assertThat(reconciler.reconcile().resolved()).isEqualTo(1);
        assertState("PAID", "APPROVED");
    }

    private ResultActions confirm(long orderId, long amount) throws Exception {
        return confirm(orderId, amount, "pk-" + orderId);
    }

    private ResultActions confirm(long orderId, long amount, String paymentKey) throws Exception {
        return mvc.perform(post("/api/orders/{id}/payments", orderId)
                .header("X-Member-Id", member)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(paymentKey, amount)));
    }

    private static String body(String paymentKey, long amount) {
        return "{\"paymentKey\":\"" + paymentKey + "\",\"amount\":" + amount + "}";
    }

    private void assertState(String orderStatus, String paymentStatus) {
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .as("order status").isEqualTo(orderStatus);
        assertThat(jdbc.queryForObject("SELECT status FROM payment WHERE order_id = ?", String.class, orderId))
                .as("payment status").isEqualTo(paymentStatus);
    }

    private int remaining() {
        return jdbc.queryForObject("SELECT remaining_qty FROM drop_event WHERE id = ?", Integer.class, dropId);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class, orderId);
    }
}
