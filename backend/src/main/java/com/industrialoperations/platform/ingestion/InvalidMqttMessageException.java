package com.industrialoperations.platform.ingestion;

public class InvalidMqttMessageException extends RuntimeException {
    public InvalidMqttMessageException(String message) {
        super(message);
    }

    public InvalidMqttMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
