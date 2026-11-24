package com.cutline.payment;

import com.cutline.order.OrderRefundRequested;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 결제된 주문의 취소가 커밋된 뒤 PG 환불을 요청한다. 실패하면 복구 작업이 다시 시도한다. */
@Component
class RefundOnCancel {

    private final JdbcTemplate jdbc;
    private final PaymentService payments;

    RefundOnCancel(JdbcTemplate jdbc, PaymentService payments) {
        this.jdbc = jdbc;
        this.payments = payments;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(OrderRefundRequested event) {
        jdbc.queryForList("SELECT pg_payment_key FROM payment WHERE order_id = ? AND status = 'APPROVED'",
                        String.class, event.orderId())
                .forEach(paymentKey -> payments.refund(event.orderId(), paymentKey));
    }
}
