package com.cutline.payment.pg;

/**
 * 외부 PG(결제대행사) 경계. 1단계는 가짜 구현, 3단계에서 토스페이먼츠 구현으로 바꾼다.
 *
 * <p>모든 호출은 주문 ID를 멱등 키로 쓴다. 같은 주문으로 승인을 두 번 요청해도 돈은 한 번만 나가야 한다.
 * 응답을 받지 못하면 {@link PgTimeoutException}을 던진다. 이때 결과는 "모름"이며, 실패로 단정하면 안 된다.
 */
public interface PaymentGateway {

    /** 결제 승인. 승인 또는 거절을 돌려주고, 결과를 모르면 {@link PgTimeoutException}. */
    PgResult approve(String paymentKey, long orderId, long amount);

    /** 주문 ID로 결제 결과 조회. 응답 유실 후 복구에 쓴다. */
    PgResult inquire(long orderId);

    /** 승인 취소(환불). 이미 취소된 결제를 다시 취소해도 성공으로 본다. */
    void cancel(String paymentKey, long orderId, String reason);
}
