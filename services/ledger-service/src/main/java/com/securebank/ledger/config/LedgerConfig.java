package com.securebank.ledger.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LedgerProperties.class)
public class LedgerConfig {

    /** Injectable clock so timestamps are always UTC and can be replaced in tests. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
