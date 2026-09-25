package com.industrialoperations.platform.telemetry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TelemetryReceivedEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        Instant receivedAt,
        Source source,
        Payload payload) {

    public static final String EVENT_TYPE = "TelemetryReceived";
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static TelemetryReceivedEvent of(
            String sourceMessageId,
            String sensorId,
            UUID machineId,
            Instant occurredAt,
            Instant receivedAt,
            BigDecimal temperature,
            BigDecimal vibration) {
        return new TelemetryReceivedEvent(
                UUID.randomUUID(),
                EVENT_TYPE,
                CURRENT_SCHEMA_VERSION,
                occurredAt,
                receivedAt,
                new Source(sourceMessageId, sensorId, machineId),
                new Payload(temperature, vibration));
    }

    public record Source(String sourceMessageId, String sensorId, UUID machineId) {
    }

    public record Payload(BigDecimal temperature, BigDecimal vibration) {
    }
}
