package com.cutline.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 동시성·정합성 테스트는 H2가 아니라 실제 MySQL에서만 돌린다.
 * 락 동작과 CHECK 제약이 운영 DB와 같아야 테스트가 의미가 있기 때문이다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlTestContainer {

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return new MySQLContainer("mysql:8.4")
                .withCommand("--default-time-zone=+00:00");
    }
}
