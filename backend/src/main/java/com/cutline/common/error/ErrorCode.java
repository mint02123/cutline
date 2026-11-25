package com.cutline.common.error;

import org.springframework.http.HttpStatus;

/**
 * 클라이언트가 분기할 수 있는 에러 코드. 목록은 docs/errors.md와 같이 관리한다.
 */
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    SELLER_NOT_FOUND(HttpStatus.NOT_FOUND, "판매자를 찾을 수 없습니다."),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다."),
    DROP_NOT_FOUND(HttpStatus.NOT_FOUND, "드롭을 찾을 수 없습니다."),
    PRODUCT_NOT_OWNED(HttpStatus.FORBIDDEN, "다른 판매자의 상품으로는 드롭을 만들 수 없습니다."),
    MEMBER_REQUIRED(HttpStatus.UNAUTHORIZED, "회원 식별 정보가 필요합니다."),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency-Key 헤더가 필요합니다."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, "같은 멱등 키로 다른 요청을 보냈습니다."),
    REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "같은 요청을 처리하고 있습니다."),
    DROP_NOT_OPEN(HttpStatus.CONFLICT, "아직 열리지 않았거나 종료된 드롭입니다."),
    SOLD_OUT(HttpStatus.CONFLICT, "품절되었습니다."),
    PURCHASE_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "1인 구매 한도를 넘었습니다."),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    ORDER_NOT_PAYABLE(HttpStatus.CONFLICT, "결제할 수 없는 상태의 주문입니다."),
    ORDER_NOT_CANCELABLE(HttpStatus.CONFLICT, "취소할 수 없는 상태의 주문입니다."),
    HOLD_EXPIRED(HttpStatus.CONFLICT, "결제 대기 시간이 지나 주문이 만료되었습니다."),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "결제 금액이 주문 금액과 다릅니다."),
    PAYMENT_IN_PROGRESS(HttpStatus.CONFLICT, "이 주문의 결제를 처리하고 있습니다."),
    PAYMENT_DECLINED(HttpStatus.UNPROCESSABLE_CONTENT, "결제가 거절되었습니다."),
    TOO_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "주문이 몰려 잠시 받을 수 없습니다. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
