package com.cutline.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 결제 대기 시간이 지난 주문을 1초 간격으로 만료시킨다. 서버가 여러 대여도 안전하다 (OrderExpiryService 참고). */
@Component
@ConditionalOnProperty(name = "cutline.order.expiry.enabled", havingValue = "true", matchIfMissing = true)
class OrderExpirer {

    private static final Logger log = LoggerFactory.getLogger(OrderExpirer.class);

    private final OrderExpiryService service;

    OrderExpirer(OrderExpiryService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${cutline.order.expiry.interval-ms:1000}")
    void expireDue() {
        int expired = service.expireDue();
        if (expired > 0) {
            log.info("expired {} order(s)", expired);
        }
    }
}
