package com.industrialoperations.platform.telemetry;

import java.time.Instant;
import java.util.UUID;

import com.industrialoperations.platform.common.Measurements;

/** Input contract for recording one telemetry observation. */
public record RecordTelemetryCommand(
        UUID eventId,
        String sourceMessageId,
        String sensorId,
        UUID machineId,
        Instant occurredAt,
        Instant receivedAt,
        Measurements measurements) {
}
