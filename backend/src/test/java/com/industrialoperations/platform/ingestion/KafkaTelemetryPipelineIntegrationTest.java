package com.industrialoperations.platform.ingestion;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.apache.kafka.common.serialization.StringDeserializer;
import com.industrialoperations.platform.AbstractIntegrationTest;
import com.industrialoperations.platform.machine.Machine;
import com.industrialoperations.platform.machine.MachineService;
import com.industrialoperations.platform.sensor.Sensor;
import com.industrialoperations.platform.sensor.SensorService;
import com.industrialoperations.platform.state.MachineLatestState;
import com.industrialoperations.platform.state.MachineStateNotFoundException;
import com.industrialoperations.platform.state.MachineStateService;
import com.industrialoperations.platform.telemetry.TelemetryDltReplayService;
import com.industrialoperations.platform.telemetry.TelemetryReceivedEvent;
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
    private TelemetryDltReplayService replayService;

    @Autowired
    private org.springframework.kafka.config.KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired
    private org.springframework.kafka.core.KafkaTemplate<String, TelemetryReceivedEvent> kafkaTemplate;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        for (org.springframework.kafka.listener.MessageListenerContainer container : kafkaListenerEndpointRegistry
                .getListenerContainers()) {
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

    @Test
    void shouldRejectInvalidMessageAndSendToDLT() {
        Properties props = new Properties();

        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-group-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> dltConsumer = new KafkaConsumer<>(props)) {

            dltConsumer.subscribe(List.of("telemetry-events-dlt"));

            Machine newMachine = machineService.createMachine("new-machine", "mach-1");

            TelemetryReceivedEvent invalidEvent = new TelemetryReceivedEvent(UUID.randomUUID(),
                    "INVALID_EVENT_TYPE", 1, Instant.now(), Instant.now(),
                    new TelemetryReceivedEvent.Source("msg-bad-1", "sensor-bad", newMachine.getId()),
                    new TelemetryReceivedEvent.Payload(new BigDecimal("90.0"), new BigDecimal("0.04")));

            kafkaTemplate.send("telemetry-events", newMachine.getId().toString(), invalidEvent);

            List<ConsumerRecord<String, String>> dltRecords = new ArrayList<>();

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                ConsumerRecords<String, String> records = dltConsumer.poll(Duration.ofMillis(200));
                for (var r : records) {
                    if (r.key() != null && r.key().equals(newMachine.getId().toString())) {
                        dltRecords.add(r);
                    }
                }
                assertThat(dltRecords).isNotEmpty();
            });

            var record = dltRecords.get(0);

            var header = record.headers().lastHeader("kafka_dlt-exception-cause-fqcn");

            assertThat(header).isNotNull();
            assertThat(new String(header.value())).contains("InvalidTelemetryEventException");

            var history = telemetryService.getTelemetryHistory(newMachine.getId());

            assertThat(history).isEmpty();

        }
    }

    @Test
    void shouldReplayMessagesFromDltAndProcessSuccessfully() {
        Machine newMachine = machineService.createMachine("machine-1", "N1");
        Sensor newSensor = sensorService.registerSensor(newMachine.getId(), "sensor-id", "sensor-1", "multi");

        TelemetryReceivedEvent newEvent = new TelemetryReceivedEvent(UUID.randomUUID(),
                "TelemetryReceived", 1, Instant.now(), Instant.now(),
                new TelemetryReceivedEvent.Source("msg-1", "sensor-id", newMachine.getId()),
                new TelemetryReceivedEvent.Payload(new BigDecimal("90.0"), new BigDecimal("0.04")));

        kafkaTemplate.send("telemetry-events-dlt", newMachine.getId().toString(), newEvent);

        int replayedCount = replayService.replay(10);
        assertThat(replayedCount).isEqualTo(1);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var telemetryHistory = telemetryService.getTelemetryHistory(newMachine.getId());

            assertThat(telemetryHistory).hasSize(1);

            assertThat(replayService.replay(10)).isEqualTo(0);
        });
    }

    @Test
    void shouldBufferMessagesDuringConsumerDowntimeAndCatchUpOnRestart() {
        Machine newMachine = machineService.createMachine("machine-1", "ext-machine-1");
        Sensor newSensor = sensorService.registerSensor(newMachine.getId(), "sensor-id-1", "sensor-1", "multi-sensor");

        for (var container : kafkaListenerEndpointRegistry.getListenerContainers()) {
            container.stop();
        }

        kafkaTemplate.send("telemetry-events", newMachine.getId().toString(),
                new TelemetryReceivedEvent(UUID.randomUUID(),
                        "TelemetryReceived", 1, Instant.now(), Instant.now(),
                        new TelemetryReceivedEvent.Source("msg-down-1", "sensor-id-1", newMachine.getId()),
                        new TelemetryReceivedEvent.Payload(new BigDecimal("90.0"), new BigDecimal("0.04"))));

        kafkaTemplate.send("telemetry-events", newMachine.getId().toString(),
                new TelemetryReceivedEvent(UUID.randomUUID(),
                        "TelemetryReceived", 1, Instant.now().plusSeconds(1), Instant.now(),
                        new TelemetryReceivedEvent.Source("msg-down-2", "sensor-id-1", newMachine.getId()),
                        new TelemetryReceivedEvent.Payload(new BigDecimal("90.0"), new BigDecimal("0.04"))));

        kafkaTemplate.send("telemetry-events", newMachine.getId().toString(),
                new TelemetryReceivedEvent(UUID.randomUUID(),
                        "TelemetryReceived", 1, Instant.now().plusSeconds(2), Instant.now(),
                        new TelemetryReceivedEvent.Source("msg-down-3", "sensor-id-1", newMachine.getId()),
                        new TelemetryReceivedEvent.Payload(new BigDecimal("90.0"), new BigDecimal("0.04"))));

        var historyBeforeStart = telemetryService.getTelemetryHistory(newMachine.getId());
        assertThat(historyBeforeStart).isEmpty();

        for (var container : kafkaListenerEndpointRegistry.getListenerContainers()) {
            container.start();
        }

        await().atMost(Duration.ofSeconds(10)).ignoreException(MachineStateNotFoundException.class)
                .untilAsserted(() -> {
                    var historyAfterStart = telemetryService.getTelemetryHistory(newMachine.getId());
                    assertThat(historyAfterStart).hasSize(3);

                    var latestState = machineStateService.getLatestState(newMachine.getId());
                    assertThat(latestState.getSourceMessageId()).isEqualTo("msg-down-3");
                });
    }
}
