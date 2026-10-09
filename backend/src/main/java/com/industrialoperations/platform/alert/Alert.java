package com.industrialoperations.platform.alert;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "alerts")
public class Alert {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "machine_id", nullable = false, updatable = false)
    private UUID machineId;

    @Column(name = "sensor_id", nullable = false, updatable = false)
    private String sensorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, updatable = false, length = 50)
    private AlertRuleType ruleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 50)
    private AlertSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private AlertStatus status;

    @Column(name = "current_value", nullable = false)
    private BigDecimal currentValue;

    @Column(name = "threshold_value", nullable = false, updatable = false)
    private BigDecimal thresholdValue;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    @Column(name = "triggered_at", nullable = false, updatable = false)
    private Instant triggeredAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public Alert(
            UUID machineId,
            String sensorId,
            AlertRuleType ruleType,
            AlertSeverity severity,
            BigDecimal currentValue,
            BigDecimal thresholdValue,
            String message,
            Instant triggeredAt) {
        this.id = UUID.randomUUID();
        this.machineId = Objects.requireNonNull(machineId, "Machine ID cannot be null");
        this.sensorId = Objects.requireNonNull(sensorId, "Sensor ID cannot be null");
        this.ruleType = Objects.requireNonNull(ruleType, "Rule type cannot be null");
        this.severity = Objects.requireNonNull(severity, "Severity cannot be null");
        this.status = AlertStatus.ACTIVE;
        this.currentValue = Objects.requireNonNull(currentValue, "Current value cannot be null");
        this.thresholdValue = Objects.requireNonNull(thresholdValue, "Threshold value cannot be null");
        this.message = Objects.requireNonNull(message, "Message cannot be null");
        this.triggeredAt = Objects.requireNonNull(triggeredAt, "Triggered at cannot be null");
    }

    protected Alert() {
    }

    public void acknowledge(Instant acknowledgedAt) {
        if (this.status != AlertStatus.ACTIVE) {
            throw new IllegalStateException("Only ACTIVE alerts can be acknowledged. Current status: " + this.status);
        }
        this.status = AlertStatus.ACKNOWLEDGED;
        this.acknowledgedAt = Objects.requireNonNullElseGet(acknowledgedAt, Instant::now);
    }

    public void resolve(Instant resolvedAt) {
        if (this.status == AlertStatus.RESOLVED) {
            return;
        }
        this.status = AlertStatus.RESOLVED;
        this.resolvedAt = Objects.requireNonNullElseGet(resolvedAt, () -> Instant.now());
    }

    public void updateCurrentValue(BigDecimal newValue) {
        this.currentValue = Objects.requireNonNull(newValue, "New current value cannot be null");
    }

    public UUID getId() {
        return id;
    }

    public UUID getMachineId() {
        return machineId;
    }

    public String getSensorId() {
        return sensorId;
    }

    public AlertRuleType getRuleType() {
        return ruleType;
    }

    public AlertSeverity getSeverity() {
        return severity;
    }

    public AlertStatus getStatus() {
        return status;
    }

    public BigDecimal getCurrentValue() {
        return currentValue;
    }

    public BigDecimal getThresholdValue() {
        return thresholdValue;
    }

    public String getMessage() {
        return message;
    }

    public Instant getTriggeredAt() {
        return triggeredAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public Long getVersion() {
        return version;
    }
}
