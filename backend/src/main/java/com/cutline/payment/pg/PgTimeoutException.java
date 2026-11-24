package com.cutline.payment.pg;

/** PG 응답을 받지 못했다. 요청이 처리됐는지 알 수 없다. */
public class PgTimeoutException extends RuntimeException {

    public PgTimeoutException(String message) {
        super(message);
    }
}
