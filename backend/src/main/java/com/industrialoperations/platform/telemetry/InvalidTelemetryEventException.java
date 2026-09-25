package com.industrialoperations.platform.telemetry;

/**
 * A consumed telemetry event can never be processed successfully. Retrying is pointless, so the
 * Kafka error handler treats this as a non-retryable failure.
 */
public class InvalidTelemetryEventException extends RuntimeException {

    public InvalidTelemetryEventException(String message) {
        super(message);
    }
}
