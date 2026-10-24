package com.industrialoperations.platform.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

import com.industrialoperations.platform.AbstractIntegrationTest;
import com.industrialoperations.platform.machine.Machine;
import com.industrialoperations.platform.machine.MachineService;
import com.industrialoperations.platform.sensor.SensorService;

/**
 * A PostgreSQL outage is a transient infrastructure failure, not a poison pill:
 * valid telemetry
 * already in Kafka must wait for the database instead of being dead-lettered
 * (Experiment 10b).
 */
class TelemetryDatabaseOutageIntegrationTest extends AbstractIntegrationTest {

    private static final String TELEMETRY_TOPIC = "telemetry-events";
    private static final String DLT_TOPIC = "telemetry-events-dlt";
    private static final Duration OUTAGE = Duration.ofSeconds(15);

    private static final KafkaContainer KAFKA;

    static {
        KAFKA = new KafkaContainer("apache/kafka:3.7.0");
        KAFKA.start();
    }

    @DynamicPropertySource
    static void configureKafkaAndFastDatabaseFailure(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.listener.auto-startup", () -> true);

        // Fail fast while PostgreSQL is paused: production waits 30s per attempt for a
        // connection.
        registry.add("spring.datasource.hikari.connection-timeout", () -> "1000");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "500");
        // A paused container never closes its sockets; without a socket timeout a query
        // on an
        // already-borrowed connection would hang instead of failing.
        registry.add("spring.datasource.hikari.data-source-properties.socketTimeout", () -> "2");
    }

    @Autowired
    private MachineService machineService;

    @Autowired
    private SensorService sensorService;

    @Autowired
    private TelemetryService telemetryService;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired
    private KafkaTemplate<String, TelemetryReceivedEvent> kafkaTemplate;

    @BeforeEach
    void startListeners() {
        for (MessageListenerContainer container : kafkaListenerEndpointRegistry.getListenerContainers()) {
            if (!container.isRunning()) {
                container.start();
            }
        }
    }

    @Test
    void shouldHoldValidTelemetryUntilDatabaseRecoversInsteadOfDeadLettering() throws Exception {
        Machine machine = machineService.createMachine("Outage Press", "EXT-OUTAGE-001");
        sensorService.registerSensor(machine.getId(), "sensor-outage-01", "Outage Sensor", "MULTI_METRIC");
        String key = machine.getId().toString();

        TelemetryReceivedEvent event = TelemetryReceivedEvent.of("msg-outage-1", "sensor-outage-01",
                machine.getId(), Instant.now(), Instant.now(), new BigDecimal("70.0"), new BigDecimal("0.03"));

        try (KafkaConsumer<String, String> dltConsumer = dltConsumer()) {
            pausePostgres();
            try {
                kafkaTemplate.send(TELEMETRY_TOPIC, key, event).get();

                List<ConsumerRecord<String, String>> dltRecords = new ArrayList<>();
                await().during(OUTAGE).atMost(OUTAGE.plusSeconds(5)).untilAsserted(() -> {
                    for (ConsumerRecord<String, String> record : dltConsumer.poll(Duration.ofMillis(200))) {
                        if (key.equals(record.key())) {
                            dltRecords.add(record);
                        }
                    }
                    assertThat(dltRecords).as("valid telemetry dead-lettered during the outage").isEmpty();
                });
            } finally {
                unpausePostgres();
            }
        }

        await().atMost(Duration.ofSeconds(60)).ignoreExceptions().untilAsserted(() -> {
            var history = telemetryService.getTelemetryHistory(machine.getId());
            assertThat(history).hasSize(1);
            assertThat(history.get(0).getSourceMessageId()).isEqualTo("msg-outage-1");
        });
    }

    private static void pausePostgres() {
        POSTGRES.getDockerClient().pauseContainerCmd(POSTGRES.getContainerId()).exec();
    }

    private static void unpausePostgres() {
        POSTGRES.getDockerClient().unpauseContainerCmd(POSTGRES.getContainerId()).exec();
    }

    private static KafkaConsumer<String, String> dltConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-outage-test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(DLT_TOPIC));
        return consumer;
    }
}
