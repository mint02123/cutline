package com.cutline.queue;

/** 대기열 Redis에 닿지 못함 (연결 실패, 시간 초과). */
class QueueUnavailableException extends RuntimeException {

    QueueUnavailableException(Throwable cause) {
        super(cause.getMessage(), cause);
    }
}
