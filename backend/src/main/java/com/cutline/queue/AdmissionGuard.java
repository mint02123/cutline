package com.cutline.queue;

import com.cutline.common.error.CutlineException;
import com.cutline.common.error.ErrorCode;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 주문 API의 입구에서 입장 토큰을 확인한다. 주문 모듈은 대기열을 이 클래스로만 만난다 (ADR-007).
 *
 * <p>{@code cutline.queue.enforce-admission=false}이면 확인하지 않는다. 대기열 없이 2-2 관문만 재던
 * {@code drop-rush.js} 측정을 같은 조건으로 다시 돌리기 위한 스위치다.
 */
@Component
public class AdmissionGuard {

    public static final String HEADER = "X-Admission-Token";

    private final AdmissionTokens tokens;
    private final Clock clock;
    private final boolean enforce;

    AdmissionGuard(AdmissionTokens tokens, Clock clock,
                   @Value("${cutline.queue.enforce-admission:true}") boolean enforce) {
        this.tokens = tokens;
        this.clock = clock;
        this.enforce = enforce;
    }

    public void require(String token, long dropId, long memberId) {
        if (!enforce) {
            return;
        }
        switch (tokens.verify(token, dropId, memberId, clock.millis())) {
            case VALID -> {
            }
            case EXPIRED -> throw new CutlineException(ErrorCode.ADMISSION_EXPIRED);
            case INVALID -> throw new CutlineException(ErrorCode.ADMISSION_REQUIRED);
        }
    }
}
