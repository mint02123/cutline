package com.cutline.common.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시각은 항상 주입받은 Clock에서 얻는다. 오픈 시각·점유 만료 테스트에서 시간을 고정하기 위함.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
