package com.industrialoperations.platform.state;

import java.time.Instant;
import java.util.UUID;

import com.industrialoperations.platform.common.Measurements;

/** Input contract of the latest-state projection, owned by this module. */
public record LatestStateUpdate(
        UUID machineId,
        UUID telemetryId,
        UUID eventId,
        String sourceMessageId,
        String sensorId,
        Instant occurredAt,
        Instant receivedAt,
        Measurements measurements) {
}
