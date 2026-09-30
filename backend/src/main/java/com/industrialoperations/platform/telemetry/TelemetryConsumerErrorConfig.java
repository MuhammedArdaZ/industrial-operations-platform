package com.industrialoperations.platform.telemetry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Phase 1 failure classification for the telemetry consumer. Permanently
 * invalid messages are
 * skipped after being logged; every other failure keeps the default retry
 * behaviour so that a
 * transient outage still results in redelivery.
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
