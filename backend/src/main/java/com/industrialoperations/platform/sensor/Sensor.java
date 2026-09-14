package com.industrialoperations.platform.sensor;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sensors")
public class Sensor {

    @Id
    @Column(name = "sensor_id", nullable = false, updatable = false)
    private String sensorId;

    @Column(name = "machine_id", nullable = false, updatable = false)
    private UUID machineId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "type")
    private String type;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Sensor(String sensorId, UUID machineId, String name, String type) {
        if (sensorId == null || sensorId.isBlank()) {
            throw new IllegalArgumentException("Sensor ID cannot be null or blank");
        }
        if (machineId == null) {
            throw new IllegalArgumentException("Machine ID cannot be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Sensor name cannot be null or blank");
        }
        this.sensorId = sensorId;
        this.machineId = machineId;
        this.name = name;
        this.type = type;
        this.createdAt = Instant.now();
    }

    protected Sensor() {
    }
    
    public String getSensorId() {
        return sensorId;
    }

    public UUID getMachineId() {
        return machineId;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

}
