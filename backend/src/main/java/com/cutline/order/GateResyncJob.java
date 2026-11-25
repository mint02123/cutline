package com.cutline.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "cutline.gate.resync.enabled", havingValue = "true", matchIfMissing = true)
class GateResyncJob {

    private static final Logger log = LoggerFactory.getLogger(GateResyncJob.class);

    private final GateResync resync;

    GateResyncJob(GateResync resync) {
        this.resync = resync;
    }

    @Scheduled(fixedDelayString = "${cutline.gate.resync.interval-ms:5000}")
    void run() {
        try {
            GateResync.Report report = resync.resync();
            if (report.lowered() + report.raised() > 0) {
                log.info("gate resync: {}", report);
            }
        } catch (DataAccessException e) {
            // Redis가 내려가 있으면 다음 주기에 다시 한다. 그동안 주문은 MySQL 경로로 처리된다.
            log.warn("gate resync skipped: {}", e.getMessage());
        }
    }
}
