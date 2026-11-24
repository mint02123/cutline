package com.cutline.order;

/**
 * 결제된 주문이 취소되어 PG 환불이 필요하다는 사건. 취소 트랜잭션이 커밋된 뒤 결제 모듈이 받아 환불한다.
 * 이벤트가 유실돼도(서버 다운 등) 결제 복구 작업이 "승인됐는데 주문이 PAID가 아닌 결제"로 찾아 환불한다.
 */
public record OrderRefundRequested(long orderId) {
}
