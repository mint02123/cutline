package com.cutline.order;

/** Redis 관문에 닿지 못함 (연결 실패, 시간 초과). 호출하는 쪽은 MySQL 경로로 처리한다 (ADR-010). */
public class GateUnavailableException extends RuntimeException {

    GateUnavailableException(Throwable cause) {
        super(cause.getMessage(), cause);
    }
}
