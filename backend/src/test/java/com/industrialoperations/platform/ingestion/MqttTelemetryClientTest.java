package com.industrialoperations.platform.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// JUnit 5 extension mechanism: integrates Mockito into test lifecycle,
// initializes @Mock fields before each test and verifies strict stubs afterwards.
@ExtendWith(MockitoExtension.class)
class MqttTelemetryClientTest {

    private static final String TOPIC = "industrial/v1/telemetry/sensor-1";
    private static final String PAYLOAD = """
            {"sourceMessageId":"msg-1","sensorId":"sensor-1",\
            "occurredAt":"2026-09-16T12:00:00Z",\
            "measurements":{"temperature":72.5,"vibration":0.3}}""";

    // Sole collaborator under test. A real instance would pull
    // the full ObjectMapper + SensorService + Kafka + Clock chain.
    @Mock
    private MqttIngestionAdapter ingestionAdapter;

    private MqttTelemetryClient client;

    @BeforeEach
    void setUp() {
        // @InjectMocks is deliberately avoided: messageArrived does not touch properties,
        // wiring manually makes this design dependency explicit.
        // start() is not called: @PostConstruct only runs in Spring container,
        // and messageArrived does not depend on the underlying MQTT client socket.
        client = new MqttTelemetryClient(
                new MqttProperties("tcp://localhost:1883", "test-client", TOPIC, 1),
                ingestionAdapter);
    }

    @Test
    void shouldForwardRawPayloadToIngestionAdapter() {
        // given
        byte[] payloadBytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        MqttMessage message = new MqttMessage(payloadBytes);

        // when
        client.messageArrived(TOPIC, message);

        // then
        ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(ingestionAdapter).handle(eq(TOPIC), payloadCaptor.capture());

        assertThat(new String(payloadCaptor.getValue(), StandardCharsets.UTF_8))
                .isEqualTo(PAYLOAD);
    }

    @Test
    void shouldConsumeInvalidMessageWithoutPropagating() {
        // given: contract violation is a permanent error; redelivering
        // creates an infinite loop, so it is swallowed and logged at the transport layer.
        doThrow(new InvalidMqttMessageException("sensor is not registered"))
                .when(ingestionAdapter).handle(any(), any());

        // when + then
        assertThatCode(() -> client.messageArrived(TOPIC, new MqttMessage(PAYLOAD.getBytes(StandardCharsets.UTF_8))))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldPropagateTransientFailureSoTheMessageIsNotAcknowledged() {
        // given: transient infrastructure failure (e.g. Kafka unreachable). Not the message's fault,
        // so it must not be acknowledged: propagate failure back to Paho client.
        doThrow(new IllegalStateException("kafka unavailable"))
                .when(ingestionAdapter).handle(any(), any());

        // when + then
        assertThatThrownBy(
                () -> client.messageArrived(TOPIC, new MqttMessage(PAYLOAD.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("kafka unavailable");
    }
}
