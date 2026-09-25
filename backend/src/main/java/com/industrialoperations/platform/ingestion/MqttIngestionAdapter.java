package com.industrialoperations.platform.ingestion;

import java.io.IOException;
import java.time.Clock;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.industrialoperations.platform.sensor.Sensor;
import com.industrialoperations.platform.sensor.SensorService;
import com.industrialoperations.platform.telemetry.TelemetryEventPublisher;
import com.industrialoperations.platform.telemetry.TelemetryReceivedEvent;

/**
 * Validates the external MQTT telemetry contract, resolves the owning machine from platform-owned
 * sensor configuration, and hands a complete internal event to the Kafka publisher.
 */
@Component
public class MqttIngestionAdapter {
    private static final String TOPIC_PREFIX = "industrial/v1/telemetry/";

    private final ObjectMapper objectMapper;
    private final SensorService sensorService;
    private final TelemetryEventPublisher telemetryEventPublisher;
    private final Clock clock;

    public MqttIngestionAdapter(ObjectMapper objectMapper, SensorService sensorService,
            TelemetryEventPublisher telemetryEventPublisher, Clock clock) {
        this.objectMapper = objectMapper;
        this.sensorService = sensorService;
        this.telemetryEventPublisher = telemetryEventPublisher;
        this.clock = clock;
    }

    public void handle(String topic, byte[] payload) {
        String topicSensorId = sensorIdFromTopic(topic);

        MqttTelemetryPayload message = parse(payload);

        validate(topicSensorId, message);

        Sensor sensor = sensorService.findSensor(topicSensorId)
                .orElseThrow(() -> reject(topicSensorId, message, "sensor is not registered"));

        TelemetryReceivedEvent event = TelemetryReceivedEvent.of(message.sourceMessageId(), sensor.getSensorId(),
                sensor.getMachineId(), message.occurredAt(), clock.instant(), message.measurements().temperature(),
                message.measurements().vibration());

        telemetryEventPublisher.publish(event);
    }

    private String sensorIdFromTopic(String topic) {
        if (topic == null || !topic.startsWith(TOPIC_PREFIX)) {
            throw new InvalidMqttMessageException("Rejected MQTT message: unexpected topic [" + topic + "]");
        }

        String sensorId = topic.substring(TOPIC_PREFIX.length());
        if (sensorId.isBlank() || sensorId.contains("/")) {
            throw new InvalidMqttMessageException("Rejected MQTT message: unexpected topic [" + topic + "]");
        }
        return sensorId;
    }

    private MqttTelemetryPayload parse(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new InvalidMqttMessageException("Rejected MQTT message: payload is empty");
        }
        try {
            return objectMapper.readValue(payload, MqttTelemetryPayload.class);
        } catch (IOException e) {
            throw new InvalidMqttMessageException("Rejected MQTT message: payload is not valid telemetry JSON", e);
        }
    }

    /**
     * Enforces the Phase 1 MQTT contract: every field the internal event needs must be present, and
     * the sensor identity in the topic must agree with the one in the payload.
     */
    private void validate(String topicSensorId, MqttTelemetryPayload message) {
        if (message == null) {
            throw new InvalidMqttMessageException("Rejected MQTT message: payload body is null");
        }
        if (isBlank(message.sourceMessageId())) {
            throw reject(topicSensorId, message, "sourceMessageId is missing");
        }
        if (isBlank(message.sensorId())) {
            throw reject(topicSensorId, message, "sensorId is missing");
        }
        if (!topicSensorId.equals(message.sensorId())) {
            throw reject(topicSensorId, message,
                    "payload sensorId [" + message.sensorId() + "] does not match the topic sensorId");
        }
        if (message.occurredAt() == null) {
            throw reject(topicSensorId, message, "occurredAt is missing");
        }
        if (message.measurements() == null) {
            throw reject(topicSensorId, message, "measurements are missing");
        }
        if (message.measurements().temperature() == null) {
            throw reject(topicSensorId, message, "temperature is missing");
        }
        if (message.measurements().vibration() == null) {
            throw reject(topicSensorId, message, "vibration is missing");
        }
    }

    private InvalidMqttMessageException reject(String topicSensorId, MqttTelemetryPayload message, String reason) {
        String sourceMessageId = message == null ? null : message.sourceMessageId();
        return new InvalidMqttMessageException(
                "Rejected MQTT message [sensorId=%s, sourceMessageId=%s]: %s"
                        .formatted(topicSensorId, sourceMessageId, reason));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
