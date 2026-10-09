package com.industrialoperations.platform.alert;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AlertResponse(
        UUID id,
        UUID machineId,
        String sensorId,
        AlertRuleType ruleType,
        AlertSeverity severity,
        AlertStatus status,
        BigDecimal currentValue,
        BigDecimal thresholdValue,
        String message,
        Instant triggeredAt,
        Instant resolvedAt,
        Instant acknowledgedAt) {

    public static AlertResponse from(Alert alert) {
        return new AlertResponse(
                alert.getId(),
                alert.getMachineId(),
                alert.getSensorId(),
                alert.getRuleType(),
                alert.getSeverity(),
                alert.getStatus(),
                alert.getCurrentValue(),
                alert.getThresholdValue(),
                alert.getMessage(),
                alert.getTriggeredAt(),
                alert.getResolvedAt(),
                alert.getAcknowledgedAt());
    }
}
