package com.industrialoperations.platform.state;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "machine_latest_state")
public class MachineLatestState {

    @Id
    @Column(name = "machine_id", nullable = false)
    private UUID machineId;

    @Column(name = "telemetry_id", nullable = false)
    private UUID telemetryId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "source_message_id", nullable = false)
    private String sourceMessageId;

    @Column(name = "sensor_id", nullable = false)
    private String sensorId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "temperature", nullable = false)
    private BigDecimal temperature;

    @Column(name = "vibration", nullable = false)
    private BigDecimal vibration;

    protected MachineLatestState() {
    }

    public MachineLatestState(UUID machineId, UUID telemetryId, UUID eventId, String sourceMessageId,
                              String sensorId, Instant occurredAt, Instant receivedAt,
                              BigDecimal temperature, BigDecimal vibration) {
        this.machineId = Objects.requireNonNull(machineId, "machineId cannot be null");
        this.telemetryId = Objects.requireNonNull(telemetryId, "telemetryId cannot be null");
        this.eventId = Objects.requireNonNull(eventId, "eventId cannot be null");
        this.sourceMessageId = Objects.requireNonNull(sourceMessageId, "sourceMessageId cannot be null");
        this.sensorId = Objects.requireNonNull(sensorId, "sensorId cannot be null");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt cannot be null");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt cannot be null");
        this.temperature = Objects.requireNonNull(temperature, "temperature cannot be null");
        this.vibration = Objects.requireNonNull(vibration, "vibration cannot be null");
    }

    public void update(UUID telemetryId, UUID eventId, String sourceMessageId,
                       String sensorId, Instant occurredAt, Instant receivedAt,
                       BigDecimal temperature, BigDecimal vibration) {
        this.telemetryId = Objects.requireNonNull(telemetryId, "telemetryId cannot be null");
        this.eventId = Objects.requireNonNull(eventId, "eventId cannot be null");
        this.sourceMessageId = Objects.requireNonNull(sourceMessageId, "sourceMessageId cannot be null");
        this.sensorId = Objects.requireNonNull(sensorId, "sensorId cannot be null");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt cannot be null");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt cannot be null");
        this.temperature = Objects.requireNonNull(temperature, "temperature cannot be null");
        this.vibration = Objects.requireNonNull(vibration, "vibration cannot be null");
    }

    public UUID getMachineId() {
        return machineId;
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
