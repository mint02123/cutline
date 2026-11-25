package com.cutline.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 만료·취소로 MySQL 재고가 돌아온 뒤 Redis 관문 토큰도 돌려준다 (ADR-009).
 * 커밋 뒤에만 돌려준다. 커밋 전에 돌려주면 롤백됐을 때 Redis가 MySQL보다 많아진다(더 파는 쪽).
 */
@Component
class GateTokenReturn {

    private static final Logger log = LoggerFactory.getLogger(GateTokenReturn.class);

    private final StockGate gate;

    GateTokenReturn(StockGate gate) {
        this.gate = gate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(HoldRelease.HoldReleased event) {
        try {
            gate.release(event.dropId(), event.memberId(), event.qty());
        } catch (DataAccessException e) {
            log.warn("failed to return gate token after release {}: {}", event, e.getMessage());
        }
    }
}
