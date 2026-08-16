package com.giri.oms.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * A single injectable Clock bean, same convention as every other service in
 * this system — every "now" in this codebase (Notification.sentAt,
 * NotificationServiceImpl, etc.) reads through this rather than
 * LocalDateTime.now()/Instant.now() directly, so tests can substitute
 * Clock.fixed(...) instead of asserting against a moving target.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
