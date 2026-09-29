package com.industrialoperations.platform.telemetry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.industrialoperations.platform.common.Measurements;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "telemetry", uniqueConstraints = {
        @UniqueConstraint(name = "uq_telemetry_sensor_source", columnNames = { "sensor_id", "source_message_id" }),
        @UniqueConstraint(name = "uq_telemetry_event_id", columnNames = { "event_id" })
})
public class Telemetry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID telemetryId;
    @Column(name = "event_id", updatable = false, nullable = false)
    private UUID eventId;
    @Column(name = "source_message_id", nullable = false, updatable = false)
    private String sourceMessageId;
    @Column(name = "sensor_id", nullable = false, updatable = false)
    private String sensorId;
    @Column(name = "machine_id", nullable = false, updatable = false)
    private UUID machineId;
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;
    @Column(name = "temperature", nullable = false, updatable = false)
    private BigDecimal temperature;
    @Column(name = "vibration", nullable = false, updatable = false)
    private BigDecimal vibration;

    public Telemetry(UUID eventId, String sourceMessageId, String sensorId, UUID machineId, Instant occurredAt,
            Instant receivedAt, Measurements measurements) {
        Objects.requireNonNull(measurements);
        this.telemetryId = UUID.randomUUID();
        this.eventId = Objects.requireNonNull(eventId);
        this.sourceMessageId = Objects.requireNonNull(sourceMessageId);
        this.sensorId = Objects.requireNonNull(sensorId);
        this.machineId = Objects.requireNonNull(machineId);
        this.occurredAt = Objects.requireNonNull(occurredAt);
        this.receivedAt = Objects.requireNonNull(receivedAt);
        this.temperature = measurements.temperature();
        this.vibration = measurements.vibration();
    }

    protected Telemetry() {
    }

    public UUID getTelemetryId() {
        return telemetryId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getSourceMessageId() {
        return sourceMessageId;
    }

    public String getSensorId() {
        return sensorId;
    }

    public UUID getMachineId() {
        return machineId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public BigDecimal getTemperature() {
        return temperature;
    }

    public BigDecimal getVibration() {
        return vibration;
    }

}
