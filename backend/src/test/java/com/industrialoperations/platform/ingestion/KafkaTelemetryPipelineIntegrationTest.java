package com.industrialoperations.platform.ingestion;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.industrialoperations.platform.AbstractIntegrationTest;
import com.industrialoperations.platform.machine.Machine;
import com.industrialoperations.platform.machine.MachineService;
import com.industrialoperations.platform.sensor.SensorService;
import com.industrialoperations.platform.state.MachineLatestState;
import com.industrialoperations.platform.state.MachineStateNotFoundException;
import com.industrialoperations.platform.state.MachineStateService;
import com.industrialoperations.platform.telemetry.TelemetryService;

class KafkaTelemetryPipelineIntegrationTest extends AbstractIntegrationTest {

    // Kafka Testcontainers instance for pipeline testing
    private static final KafkaContainer KAFKA;

    static {
        KAFKA = new KafkaContainer("apache/kafka:3.7.0");
        KAFKA.start();
    }

    @DynamicPropertySource
    static void configureKafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.listener.auto-startup", () -> true);
    }

    @Autowired
    private MqttIngestionAdapter ingestionAdapter;

    @Autowired
    private MachineService machineService;

    @Autowired
    private SensorService sensorService;

    @Autowired
    private MachineStateService machineStateService;

    @Autowired
    private TelemetryService telemetryService;

    @Autowired
    private org.springframework.kafka.config.KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        for (org.springframework.kafka.listener.MessageListenerContainer container : kafkaListenerEndpointRegistry.getListenerContainers()) {
            if (!container.isRunning()) {
                container.start();
            }
        }
    }

    @Test
    @DisplayName("Should ingest MQTT payload, publish to Kafka, consume and persist to Postgres latest state and history")
    void shouldIngestMqttPayloadAndProcessThroughKafkaToDatabase() {
        Machine machine = machineService.createMachine("CNC Milling 01", "EXT-TEST-001");
        sensorService.registerSensor(machine.getId(), "sensor-pipe-01", "Multi Sensor", "MULTI_METRIC");

        String payload = """
                    {
                        "sourceMessageId": "msg-001",
                        "sensorId": "sensor-pipe-01",
                        "occurredAt": "2026-09-27T12:00:00Z",
                        "measurements": {
                            "temperature": 75.5,
                            "vibration": 0.025
                        }
                    }
                """;

        ingestionAdapter.handle("industrial/v1/telemetry/sensor-pipe-01", payload.getBytes(StandardCharsets.UTF_8));

        await()
                .atMost(Duration.ofSeconds(10))
                .ignoreException(MachineStateNotFoundException.class)
                .untilAsserted(() -> {
            MachineLatestState machineLatestState = machineStateService.getLatestState(machine.getId());

            assertThat(machineLatestState.getSensorId()).isEqualTo("sensor-pipe-01");
            assertThat(machineLatestState.getTemperature()).isEqualByComparingTo("75.5");
            assertThat(machineLatestState.getVibration()).isEqualByComparingTo("0.025");
        });

        var history = telemetryService.getTelemetryHistory(machine.getId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getSourceMessageId()).isEqualTo("msg-001");
    }
}
