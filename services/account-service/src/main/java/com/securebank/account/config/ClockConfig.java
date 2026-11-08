package com.securebank.account.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** UTC clock; injected everywhere time is needed so tests can replace it. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
