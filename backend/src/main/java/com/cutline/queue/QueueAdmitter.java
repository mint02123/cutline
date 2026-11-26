package com.cutline.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 입장 스케줄러. 서버마다 하나씩 돌아도 된다. 입장 인원은 Redis에 기록된 지난 입장 시각으로 정하므로
 * 전체 입장 속도는 서버 수와 상관없이 설정값이다 ({@link QueueStore}의 ADMIT 스크립트).
 */
@Component
@ConditionalOnProperty(name = "cutline.queue.admitter.enabled", havingValue = "true", matchIfMissing = true)
class QueueAdmitter {

    private static final Logger log = LoggerFactory.getLogger(QueueAdmitter.class);

    private final QueueService service;

    QueueAdmitter(QueueService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${cutline.queue.admitter.interval-ms:100}")
    void run() {
        try {
            service.admitTick();
        } catch (QueueUnavailableException e) {
            // Redis 장애 중에는 대기열을 건너뛰고 입장시키므로(ADR-012) 여기서 할 일이 없다. 다음 주기에 다시 한다.
            log.debug("queue admitter skipped: {}", e.getMessage());
        }
    }
}
