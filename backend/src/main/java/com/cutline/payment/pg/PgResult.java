package com.cutline.payment.pg;

public record PgResult(Status status, String paymentKey, long amount) {

    public enum Status {
        APPROVED,
        DECLINED,
        CANCELED,
        NOT_FOUND
    }

    public static PgResult of(Status status, String paymentKey, long amount) {
        return new PgResult(status, paymentKey, amount);
    }
}
