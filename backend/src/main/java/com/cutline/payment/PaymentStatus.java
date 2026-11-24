package com.cutline.payment;

public enum PaymentStatus {
    /** PG에 승인을 요청하기 직전에 기록. 이 상태로 오래 남았다면 서버가 도중에 죽은 것이다. */
    REQUESTED,
    APPROVED,
    /** PG 응답을 받지 못했다. 돈이 나갔는지 모르므로 주문 만료 대상에서 빠지고, 복구 작업이 PG에 조회한다. */
    UNKNOWN,
    FAILED,
    /** 승인 후 취소(보상). */
    CANCELED
}
