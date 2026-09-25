package com.industrialoperations.platform.ingestion;

import java.math.BigDecimal;
import java.time.Instant;

public record MqttTelemetryPayload(
        String sourceMessageId,
        String sensorId,
        Instant occurredAt,
        Measurements measurements) {

    public record Measurements(
            BigDecimal temperature,
            BigDecimal vibration) {

    }
}
