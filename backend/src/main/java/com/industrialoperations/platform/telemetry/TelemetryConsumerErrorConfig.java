package com.industrialoperations.platform.telemetry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Failure classification for the telemetry consumer (ADR-006).
 *
 * <ul>
 * <li>{@link InvalidTelemetryEventException}: the message itself is bad, so it goes straight to
 * {@code telemetry-events-dlt} without retries.</li>
 * <li>Transient database failures ({@link CannotCreateTransactionException},
 * {@link DataAccessResourceFailureException}): the message is valid and PostgreSQL is
 * unreachable, so the record is retried with an exponential back-off and never dead-lettered.
 * The consumer holds its partition position until the database returns, which preserves
 * per-machine ordering (ADR-004) and needs no manual replay. Each interval is capped at 30s so
 * that one failed attempt plus one back-off stays well below {@code max.poll.interval.ms}.</li>
 * <li>Any other failure is retried twice, one second apart, before being dead-lettered.</li>
 * </ul>
 */
@Configuration
public class TelemetryConsumerErrorConfig {
    private static final long DATABASE_RETRY_INITIAL_INTERVAL_MS = 1000L;
    private static final double DATABASE_RETRY_MULTIPLIER = 2.0;
    private static final long DATABASE_RETRY_MAX_INTERVAL_MS = 30_000L;
    private static final long DATABASE_RETRY_MAX_ELAPSED_TIME_MS = Long.MAX_VALUE;

    @Bean
    DefaultErrorHandler telemetryConsumerErrorHandler(KafkaOperations<Object, Object> kafkaOperations) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaOperations);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
        errorHandler.addNotRetryableExceptions(InvalidTelemetryEventException.class);

        BackOff databaseBackOff = databaseOutageBackOff();

        errorHandler.setBackOffFunction(
                (record, exception) -> isTransientDatabaseFailure(exception) ? databaseBackOff : null);

        return errorHandler;
    }

    private static boolean isTransientDatabaseFailure(Exception exception) {
        return exception instanceof CannotCreateTransactionException
                || exception instanceof DataAccessResourceFailureException;
    }

    private static BackOff databaseOutageBackOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(DATABASE_RETRY_INITIAL_INTERVAL_MS,
                DATABASE_RETRY_MULTIPLIER);

        backOff.setMaxInterval(DATABASE_RETRY_MAX_INTERVAL_MS);
        backOff.setMaxElapsedTime(DATABASE_RETRY_MAX_ELAPSED_TIME_MS);

        return backOff;
    }
}
