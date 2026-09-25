package com.industrialoperations.platform.ingestion;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.industrialoperations.platform.sensor.Sensor;
import com.industrialoperations.platform.sensor.SensorService;
import com.industrialoperations.platform.telemetry.TelemetryEventPublisher;
import com.industrialoperations.platform.telemetry.TelemetryReceivedEvent;

@ExtendWith(MockitoExtension.class)
public class MqttIngestionAdapterTest {
    @Mock
    private SensorService sensorService;
    @Mock
    private TelemetryEventPublisher telemetryEventPublisher;
    private MqttIngestionAdapter adapter;

    private static final Instant FIXED_NOW = Instant.parse("2026-09-16T12:00:05Z");
    private static final UUID MACHINE_ID = UUID.randomUUID();
    private static final String PAYLOAD = """
                {
                    "sourceMessageId": "msg-001",
                    "sensorId": "sensor-1",
                    "occurredAt": "2026-09-16T12:00:00Z",
                    "measurements": {
                        "temperature": 75.5,
                        "vibration": 0.02
                    }
                }
            """;

    @BeforeEach
    void setUp() {
        adapter = new MqttIngestionAdapter(
                new ObjectMapper().findAndRegisterModules(),
                sensorService,
                telemetryEventPublisher,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldResolveMachineFromSensorConfigurationAndPublishEvent() {
        when(sensorService.findSensor("sensor-1"))
                .thenReturn(Optional.of(new Sensor("sensor-1", MACHINE_ID, "name", "type-1")));

        adapter.handle("industrial/v1/telemetry/sensor-1", json(PAYLOAD));

        ArgumentCaptor<TelemetryReceivedEvent> captor = ArgumentCaptor.forClass(TelemetryReceivedEvent.class);

        verify(telemetryEventPublisher).publish(captor.capture());

        TelemetryReceivedEvent event = captor.getValue();

        assertThat(event.source().machineId()).isEqualTo(MACHINE_ID);
        assertThat(event.receivedAt()).isEqualTo(FIXED_NOW);
        assertThat(event.payload().temperature()).isEqualByComparingTo("75.5");
        assertThat(event.payload().vibration()).isEqualByComparingTo("0.02");
    }

    @Test
    void shouldRejectWhenTopicAndPayloadSensorIdMismatch() {
        assertThrows(InvalidMqttMessageException.class,
                () -> adapter.handle("industrial/v1/telemetry/sensor-x", json(PAYLOAD)));

        verify(telemetryEventPublisher, never()).publish(any());
    }

    @Test
    void shouldRejectWhenSensorIsNotRegistered() {
        when(sensorService.findSensor("sensor-1")).thenReturn(Optional.empty());

        assertThrows(InvalidMqttMessageException.class,
                () -> adapter.handle("industrial/v1/telemetry/sensor-1", json(PAYLOAD)));

        verify(telemetryEventPublisher, never()).publish(any());
    }

    @Test
    void shouldRejectWhenTopicIsMalformed() {
        assertThrows(InvalidMqttMessageException.class,
                () -> adapter.handle("industrial/v1/telemetry", json(PAYLOAD)));

        verify(telemetryEventPublisher, never()).publish(any());
    }

    @Test
    void shouldRejectWhenPayloadIsEmpty() {
        assertThrows(InvalidMqttMessageException.class,
                () -> adapter.handle("industrial/v1/telemetry/sensor-1", new byte[0]));

        verify(telemetryEventPublisher, never()).publish(any());
    }

    @Test
    void shouldRejectWhenPayloadIsMalformedJson() {
        assertThrows(InvalidMqttMessageException.class,
                () -> adapter.handle("industrial/v1/telemetry/sensor-1", json("{ not valid json :::: }")));

        verify(telemetryEventPublisher, never()).publish(any());
    }

    @Test
    void shouldRejectWhenRequiredFieldIsMissing() {
        String missingMeasurements = """
                {
                    "sourceMessageId": "msg-001",
                    "sensorId": "sensor-1",
                    "occurredAt": "2026-09-16T12:00:00Z"
                }
                """;

        assertThrows(InvalidMqttMessageException.class,
                () -> adapter.handle("industrial/v1/telemetry/sensor-1", json(missingMeasurements)));

        verify(telemetryEventPublisher, never()).publish(any());
    }

    private byte[] json(String jsonString) {
        return jsonString.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}