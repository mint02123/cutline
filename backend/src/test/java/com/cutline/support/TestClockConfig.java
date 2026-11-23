package com.cutline.support;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

    public static final Instant BASE_TIME = Instant.parse("2026-12-01T00:00:00Z");

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(BASE_TIME);
    }
}
