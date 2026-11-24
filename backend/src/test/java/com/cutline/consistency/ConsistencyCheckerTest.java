package com.cutline.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.cutline.order.OrderPlacementService;
import com.cutline.payment.PaymentService;
import com.cutline.payment.pg.FakePaymentGateway;
import com.cutline.support.DatabaseCleaner;
import com.cutline.support.Fixtures;
import com.cutline.support.IntegrationTest;
import com.cutline.support.MutableClock;
import com.cutline.support.TestClockConfig;
import java.sql.Timestamp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** 체커가 정상 상태에서는 조용하고, 각 규칙이 실제로 틀어진 데이터를 잡는지 확인한다. */
@IntegrationTest
class ConsistencyCheckerTest {

    @Autowired
    ConsistencyChecker checker;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    OrderPlacementService placement;

    @Autowired
    PaymentService payments;

    @Autowired
    FakePaymentGateway pg;

    long dropId;
    long paidOrder;
    long pendingOrder;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        pg.reset();
        clock.set(TestClockConfig.BASE_TIME);
        Fixtures fx = new Fixtures(jdbc, TestClockConfig.BASE_TIME);
        dropId = fx.openDrop(219_000, 10, 1);
        long a = fx.member();
        paidOrder = placement.place(a, dropId, 1, "k").orderId();
        payments.confirm(a, paidOrder, "pk-1", 219_000);
        pendingOrder = placement.place(fx.member(), dropId, 1, "k").orderId();
    }

    @Test
    void 정상_상태에서는_위반이_없고_API로도_확인된다() throws Exception {
        assertThat(checker.check()).isEmpty();
        mvc.perform(get("/internal/consistency"))
                .andExpect(jsonPath("$.consistent").value(true))
                .andExpect(jsonPath("$.rulesChecked").value(ConsistencyChecker.RULES.size()));
    }

    @Test
    void 재고가_새면_잡는다() {
        jdbc.update("UPDATE drop_event SET remaining_qty = remaining_qty + 1 WHERE id = ?", dropId);
        assertThat(rules()).containsExactly("stock_conservation");
    }

    @Test
    void 한도_장부가_주문과_어긋나면_잡는다() {
        jdbc.update("UPDATE member_drop_quota SET used_qty = 0 WHERE drop_id = ?", dropId);
        assertThat(rules()).containsOnly("quota_matches_orders");
    }

    @Test
    void 결제_없는_PAID와_환불_누락과_미해결_결제를_잡는다() {
        jdbc.update("UPDATE payment SET status = 'UNKNOWN' WHERE order_id = ?", paidOrder);
        assertThat(rules()).containsExactlyInAnyOrder("paid_has_approved_payment", "no_unresolved_payment");

        jdbc.update("UPDATE payment SET status = 'APPROVED' WHERE order_id = ?", paidOrder);
        jdbc.update("UPDATE orders SET status = 'EXPIRED' WHERE id = ?", paidOrder);
        assertThat(rules()).contains("approved_payment_has_paid_order");
    }

    @Test
    void 결제_금액이_주문과_다르면_잡는다() {
        jdbc.update("UPDATE payment SET amount = 1 WHERE order_id = ?", paidOrder);
        assertThat(rules()).containsExactly("paid_has_approved_payment");
    }

    @Test
    void 운영용_SQL_문서에_모든_규칙이_있다() throws Exception {
        String doc = java.nio.file.Files.readString(java.nio.file.Path.of("../docs/consistency-checks.sql"));
        assertThat(ConsistencyChecker.RULES).allMatch(rule -> doc.contains("[" + rule.name() + "]"));
    }

    private java.util.List<String> rules() {
        return checker.check().stream().map(ConsistencyChecker.Violation::rule).toList();
    }
}
