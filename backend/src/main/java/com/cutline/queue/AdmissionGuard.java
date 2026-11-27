package com.cutline.queue;

import com.cutline.common.error.CutlineException;
import com.cutline.common.error.ErrorCode;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AdmissionGuard.class);

    private final AdmissionTokens tokens;
    private final QueueStore store;
    private final Clock clock;
    private final boolean enforce;

    AdmissionGuard(AdmissionTokens tokens, QueueStore store, Clock clock,
                   @Value("${cutline.queue.enforce-admission:true}") boolean enforce) {
        this.tokens = tokens;
        this.store = store;
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

    /**
     * 입장한 회원이 주문을 시도했다(성공이든 품절이든). 입장 스케줄러가 "주문하지 않은 입장자"로 세지 않게 한다.
     * 주문 응답을 기다리게 하지 않도록 따로 실행한다. Redis 장애 중에는 요청마다 시간 초과(500ms)를 기다리게 되기 때문이다.
     * 실패하면 넘어간다. 그 회원은 입장 만료(2분)까지 주문하지 않은 입장자로 남아 입장을 조금 덜 열 뿐이다.
     */
    public void consumed(long dropId, long memberId) {
        if (!enforce) {
            return;
        }
        Thread.ofVirtual().start(() -> {
            try {
                store.consumed(dropId, memberId);
            } catch (QueueUnavailableException e) {
                log.debug("could not mark admission consumed drop={} member={}: {}", dropId, memberId, e.getMessage());
            }
        });
    }
}
