package com.industrialoperations.platform.sensor;

public record RegisterSensorRequest(String sensorId, String name, String type) {

    public RegisterSensorRequest {
        if (sensorId == null || sensorId.isBlank()) {
            throw new IllegalArgumentException("Sensor ID cannot be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Sensor name cannot be blank");
        }
    }
}
