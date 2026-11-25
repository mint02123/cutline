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
@SpringBootTest(properties = "cutline.scheduling.enabled=false")
@AutoConfigureMockMvc
@Import({MySqlTestContainer.class, RedisTestContainer.class, TestClockConfig.class})
public @interface IntegrationTest {
}
