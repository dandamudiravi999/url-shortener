package com.urlshortener.service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /** Injected everywhere "now" matters, so expiry logic is testable without sleeps. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
