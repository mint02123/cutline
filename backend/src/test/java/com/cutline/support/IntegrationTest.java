package com.cutline.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * 실제 MySQL·Redis(Testcontainers) + 고정 시계로 돌리는 통합 테스트.
 * 스케줄러는 꺼 두고, 테스트가 필요한 작업을 직접 호출한다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "cutline.scheduling.enabled=false",
        // Redis 장애 테스트 뒤 다음 테스트가 관문을 건너뛴 상태로 시작하지 않게 짧게 둔다.
        "cutline.gate.fallback.skip-gate-for=PT0.3S",
        // 주문 테스트는 대기열 없이 주문 경로만 본다. 입장 토큰 확인은 queue 패키지 테스트가 켜고 확인한다.
        "cutline.queue.enforce-admission=false"})
@AutoConfigureMockMvc
@Import({MySqlTestContainer.class, RedisTestContainer.class, TestClockConfig.class})
public @interface IntegrationTest {
}
