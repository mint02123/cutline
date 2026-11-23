package com.cutline.common.error;

public class CutlineException extends RuntimeException {

    private final ErrorCode code;

    public CutlineException(ErrorCode code) {
        this(code, code.title());
    }

    public CutlineException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
