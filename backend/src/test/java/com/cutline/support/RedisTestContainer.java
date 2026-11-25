package com.cutline.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;

/** Redis 1차 관문 (ADR-009)용. 운영과 같은 버전의 실제 Redis에서 Lua 스크립트를 돌린다. */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestContainer {

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>("redis:7.4").withExposedPorts(6379);
    }
}
