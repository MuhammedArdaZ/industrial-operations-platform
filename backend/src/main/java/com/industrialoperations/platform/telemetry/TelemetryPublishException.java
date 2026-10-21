package com.industrialoperations.platform.telemetry;

/**
 * Publishing a telemetry event to Kafka failed or timed out. Unlike an invalid
 * message, this is a transient infrastructure failure, so it must not be silently
 * discarded.
 */
public class TelemetryPublishException extends RuntimeException {

    public TelemetryPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
