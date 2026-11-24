package com.cutline.drop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 오픈 시각이 된 드롭을 1초 간격으로 연다.
 * 주문 쪽도 opens_at을 함께 확인하므로, 이 작업이 늦어도 오픈 전에 팔리는 일은 없다.
 */
@Component
@ConditionalOnProperty(name = "cutline.drop.opener.enabled", havingValue = "true", matchIfMissing = true)
class DropOpener {

    private static final Logger log = LoggerFactory.getLogger(DropOpener.class);

    private final DropService service;

    DropOpener(DropService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${cutline.drop.opener.interval-ms:1000}")
    void openDueDrops() {
        int opened = service.openDueDrops();
        if (opened > 0) {
            log.info("opened {} drop(s)", opened);
        }
    }
}
