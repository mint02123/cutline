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
    PRODUCT_NOT_OWNED(HttpStatus.FORBIDDEN, "다른 판매자의 상품으로는 드롭을 만들 수 없습니다.");

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
