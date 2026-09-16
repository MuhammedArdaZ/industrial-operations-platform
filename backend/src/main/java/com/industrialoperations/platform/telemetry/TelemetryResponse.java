package com.industrialoperations.platform.telemetry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TelemetryResponse(
    UUID telemetryId,
    UUID machineId,
    String sensorId,
    UUID eventId,
    String sourceMessageId,
    Instant occurredAt,
    Instant receivedAt,
    BigDecimal temperature,
    BigDecimal vibration
) {
    public static TelemetryResponse from(Telemetry telemetry) {
        return new TelemetryResponse(
            telemetry.getTelemetryId(),
            telemetry.getMachineId(),
            telemetry.getSensorId(),
            telemetry.getEventId(),
            telemetry.getSourceMessageId(),
            telemetry.getOccurredAt(),
            telemetry.getReceivedAt(),
            telemetry.getTemperature(),
            telemetry.getVibration()
        );
    }
}