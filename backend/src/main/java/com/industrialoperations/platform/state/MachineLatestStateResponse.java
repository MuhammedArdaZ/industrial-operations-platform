package com.industrialoperations.platform.state;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MachineLatestStateResponse(UUID machineId, UUID telemetryId, UUID eventId, String sourceMessageId,
        String sensorId, Instant occurredAt, Instant receivedAt,
        BigDecimal temperature, BigDecimal vibration) {

    public static MachineLatestStateResponse from(MachineLatestState machineLatestState) {
        return new MachineLatestStateResponse(
                machineLatestState.getMachineId(),
                machineLatestState.getTelemetryId(),
                machineLatestState.getEventId(),
                machineLatestState.getSourceMessageId(),
                machineLatestState.getSensorId(),
                machineLatestState.getOccurredAt(),
                machineLatestState.getReceivedAt(),
                machineLatestState.getTemperature(),
                machineLatestState.getVibration());
    }
}
