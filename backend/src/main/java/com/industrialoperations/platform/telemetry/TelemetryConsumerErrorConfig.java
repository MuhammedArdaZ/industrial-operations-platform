package com.industrialoperations.platform.telemetry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Failure classification for the telemetry consumer (ADR-006). Permanently invalid messages go
 * straight to {@code telemetry-events-dlt}; every other failure is retried twice, one second
 * apart, before being dead-lettered.
 */
@Configuration
public class TelemetryConsumerErrorConfig {

    @Bean
    DefaultErrorHandler telemetryConsumerErrorHandler(KafkaOperations<Object, Object> kafkaOperations) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaOperations);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));

        errorHandler.addNotRetryableExceptions(InvalidTelemetryEventException.class);
        return errorHandler;
    }
}
