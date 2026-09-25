package com.industrialoperations.platform.telemetry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * Phase 1 failure classification for the telemetry consumer. Permanently invalid messages are
 * skipped after being logged; every other failure keeps the default retry behaviour so that a
 * transient outage still results in redelivery.
 */
@Configuration
public class TelemetryConsumerErrorConfig {

    @Bean
    DefaultErrorHandler telemetryConsumerErrorHandler() {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler();
        errorHandler.addNotRetryableExceptions(InvalidTelemetryEventException.class);
        return errorHandler;
    }
}
