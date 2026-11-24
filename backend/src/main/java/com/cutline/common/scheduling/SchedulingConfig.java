package com.cutline.common.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 백그라운드 작업(드롭 오픈, 점유 만료) 전체 스위치.
 * 통합 테스트는 이것을 끄고 작업을 직접 호출해, 실행 시점을 테스트가 통제한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "cutline.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {
}
