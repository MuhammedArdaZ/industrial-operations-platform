package com.industrialoperations.platform.alert;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AlertEvent(
        AlertEventType eventType,
        UUID alertId,
        UUID machineId,
        String sensorId,
        AlertRuleType ruleType,
        AlertSeverity severity,
        AlertStatus status,
        BigDecimal currentValue,
        BigDecimal thresholdValue,
        Instant triggeredAt,
        Instant acknowledgedAt,
        Instant resolvedAt,
        Long version) {

    public static AlertEvent from(Alert alert, AlertEventType type) {
        return new AlertEvent(
                type,
                alert.getId(),
                alert.getMachineId(),
                alert.getSensorId(),
                alert.getRuleType(),
                alert.getSeverity(),
                alert.getStatus(),
                alert.getCurrentValue(),
                alert.getThresholdValue(),
                alert.getTriggeredAt(),
                alert.getAcknowledgedAt(),
                alert.getResolvedAt(),
                alert.getVersion());
    }
}
