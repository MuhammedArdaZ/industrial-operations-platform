package com.industrialoperations.platform.sensor;

import java.time.Instant;
import java.util.UUID;

public record SensorResponse(
        String sensorId,
        UUID machineId,
        String name,
        String type,
        Instant createdAt) {

    public static SensorResponse from(Sensor sensor) {
        return new SensorResponse(
                sensor.getSensorId(),
                sensor.getMachineId(),
                sensor.getName(),
                sensor.getType(),
                sensor.getCreatedAt());
    }
}
