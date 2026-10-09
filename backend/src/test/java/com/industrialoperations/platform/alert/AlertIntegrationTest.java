package com.industrialoperations.platform.alert;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.industrialoperations.platform.AbstractIntegrationTest;
import com.industrialoperations.platform.common.Measurements;
import com.industrialoperations.platform.machine.Machine;
import com.industrialoperations.platform.machine.MachineRepository;
import com.industrialoperations.platform.sensor.Sensor;
import com.industrialoperations.platform.sensor.SensorRepository;
import com.industrialoperations.platform.state.MachineLatestStateRepository;
import com.industrialoperations.platform.telemetry.RecordTelemetryCommand;
import com.industrialoperations.platform.telemetry.TelemetryRepository;
import com.industrialoperations.platform.telemetry.TelemetryService;

@AutoConfigureMockMvc
public class AlertIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TelemetryService telemetryService;

    @Autowired
    private TelemetryRepository telemetryRepository;

    @Autowired
    private MachineLatestStateRepository machineLatestStateRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private MachineRepository machineRepository;

    @Autowired
    private SensorRepository sensorRepository;

    private Machine testMachine;
    private Sensor testSensor;

    @BeforeEach
    void setUp() {
        cleanUp();
        testMachine = machineRepository.save(new Machine("CNC-Mill-01", "REF-ALERT-01"));
        testSensor = sensorRepository.save(new Sensor("sensor-alert-01", testMachine.getId(), "Main Sensor Unit", "MULTI"));
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        alertRepository.deleteAll();
        machineLatestStateRepository.deleteAll();
        telemetryRepository.deleteAll();
        sensorRepository.deleteAll();
        machineRepository.deleteAll();
    }

    private RecordTelemetryCommand createCommand(BigDecimal temperature, BigDecimal vibration, Instant occurredAt) {
        return new RecordTelemetryCommand(
                UUID.randomUUID(),
                "msg-" + UUID.randomUUID(),
                testSensor.getSensorId(),
                testMachine.getId(),
                occurredAt,
                occurredAt.plusMillis(50),
                new Measurements(temperature, vibration));
    }

    @Test
    @DisplayName("Should trigger ACTIVE alert when temperature reaches or exceeds 90.0°C threshold")
    void shouldTriggerActiveAlertWhenTemperatureExceedsThreshold() {
        Instant now = Instant.parse("2026-10-09T10:00:00Z");
        BigDecimal highTemp = new BigDecimal("92.5");
        BigDecimal normalVib = new BigDecimal("0.20");

        telemetryService.recordTelemetry(createCommand(highTemp, normalVib, now));

        List<Alert> alerts = alertRepository.findByMachineIdOrderByTriggeredAtDesc(testMachine.getId());
        assertThat(alerts).hasSize(1);

        Alert alert = alerts.getFirst();
        assertThat(alert.getMachineId()).isEqualTo(testMachine.getId());
        assertThat(alert.getSensorId()).isEqualTo(testSensor.getSensorId());
        assertThat(alert.getRuleType()).isEqualTo(AlertRuleType.HIGH_TEMPERATURE);
        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.CRITICAL);
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.ACTIVE);
        assertThat(alert.getCurrentValue()).isEqualByComparingTo(highTemp);
        assertThat(alert.getThresholdValue()).isEqualByComparingTo(new BigDecimal("90.0"));
        assertThat(alert.getMessage()).contains("92.5");
        assertThat(alert.getTriggeredAt()).isEqualTo(now);
        assertThat(alert.getResolvedAt()).isNull();
        assertThat(alert.getAcknowledgedAt()).isNull();
    }

    @Test
    @DisplayName("Should update current value without creating duplicate alert on subsequent high temperature (Anti-Spam)")
    void shouldNotCreateDuplicateAlertOnSubsequentHighTemperatureTelemetry() {
        Instant firstTime = Instant.now();
        BigDecimal temp1 = new BigDecimal("91.0");
        telemetryService.recordTelemetry(createCommand(temp1, new BigDecimal("0.10"), firstTime));

        List<Alert> alertsAfterFirst = alertRepository.findByMachineIdOrderByTriggeredAtDesc(testMachine.getId());
        assertThat(alertsAfterFirst).hasSize(1);
        assertThat(alertsAfterFirst.getFirst().getCurrentValue()).isEqualByComparingTo(temp1);

        Instant secondTime = firstTime.plusSeconds(5);
        BigDecimal temp2 = new BigDecimal("96.5");
        telemetryService.recordTelemetry(createCommand(temp2, new BigDecimal("0.10"), secondTime));

        List<Alert> alertsAfterSecond = alertRepository.findByMachineIdOrderByTriggeredAtDesc(testMachine.getId());
        assertThat(alertsAfterSecond).hasSize(1); // Anti-spam: Still only 1 alert!
        assertThat(alertsAfterSecond.getFirst().getStatus()).isEqualTo(AlertStatus.ACTIVE);
        assertThat(alertsAfterSecond.getFirst().getCurrentValue()).isEqualByComparingTo(temp2);
    }

    @Test
    @DisplayName("Should trigger ACTIVE alert when vibration reaches or exceeds 0.80g threshold")
    void shouldTriggerActiveAlertWhenVibrationExceedsThreshold() {
        Instant now = Instant.now();
        BigDecimal normalTemp = new BigDecimal("70.0");
        BigDecimal highVib = new BigDecimal("0.85");

        telemetryService.recordTelemetry(createCommand(normalTemp, highVib, now));

        List<Alert> alerts = alertRepository.findByMachineIdOrderByTriggeredAtDesc(testMachine.getId());
        assertThat(alerts).hasSize(1);

        Alert alert = alerts.getFirst();
        assertThat(alert.getRuleType()).isEqualTo(AlertRuleType.HIGH_VIBRATION);
        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.CRITICAL);
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.ACTIVE);
        assertThat(alert.getCurrentValue()).isEqualByComparingTo(highVib);
        assertThat(alert.getThresholdValue()).isEqualByComparingTo(new BigDecimal("0.80"));
    }

    @Test
    @DisplayName("Should resolve active alerts when temperature falls below clearing threshold (Hysteresis < 85.0°C)")
    void shouldResolveAlertWhenTemperatureFallsBelowClearingThreshold() {
        Instant t1 = Instant.now();
        // 1. Eşik aşımı: 92.0°C -> Alarm tetiklenir
        telemetryService.recordTelemetry(createCommand(new BigDecimal("92.0"), new BigDecimal("0.10"), t1));
        assertThat(alertRepository.findByStatusOrderByTriggeredAtDesc(AlertStatus.ACTIVE)).hasSize(1);

        // 2. Ölü bant (Deadband): 88.0°C -> 85.0 ile 90.0 arasında hala ACTIVE kalmalı
        Instant t2 = t1.plusSeconds(5);
        telemetryService.recordTelemetry(createCommand(new BigDecimal("88.0"), new BigDecimal("0.10"), t2));
        assertThat(alertRepository.findByStatusOrderByTriggeredAtDesc(AlertStatus.ACTIVE)).hasSize(1);
        assertThat(alertRepository.findByStatusOrderByTriggeredAtDesc(AlertStatus.RESOLVED)).isEmpty();

        // 3. Eşiğin altına düşüş: 84.0°C (< 85.0°C) -> Alarm RESOLVED olmalı
        Instant t3 = t2.plusSeconds(5);
        telemetryService.recordTelemetry(createCommand(new BigDecimal("84.0"), new BigDecimal("0.10"), t3));

        assertThat(alertRepository.findByStatusOrderByTriggeredAtDesc(AlertStatus.ACTIVE)).isEmpty();
        List<Alert> resolvedAlerts = alertRepository.findByStatusOrderByTriggeredAtDesc(AlertStatus.RESOLVED);
        assertThat(resolvedAlerts).hasSize(1);
        assertThat(resolvedAlerts.getFirst().getResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should acknowledge alert via POST /api/v1/alerts/{alertId}/acknowledge REST endpoint")
    void shouldAcknowledgeAlertViaRestApi() throws Exception {
        telemetryService.recordTelemetry(createCommand(new BigDecimal("93.0"), new BigDecimal("0.10"), Instant.now()));

        List<Alert> activeAlerts = alertRepository.findByStatusOrderByTriggeredAtDesc(AlertStatus.ACTIVE);
        assertThat(activeAlerts).hasSize(1);
        UUID alertId = activeAlerts.getFirst().getId();

        mockMvc.perform(post("/api/v1/alerts/{alertId}/acknowledge", alertId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alertId.toString()))
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.acknowledgedAt").isNotEmpty());

        Alert updatedAlert = alertRepository.findById(alertId).orElseThrow();
        assertThat(updatedAlert.getStatus()).isEqualTo(AlertStatus.ACKNOWLEDGED);
        assertThat(updatedAlert.getAcknowledgedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should retrieve active alerts and machine alerts via GET endpoints")
    void shouldRetrieveActiveAlertsAndMachineAlertsViaRestApi() throws Exception {
        // Hem yüksek sıcaklık (95°C) hem yüksek titreşim (0.90g) -> 2 aktif alarm üretir
        telemetryService.recordTelemetry(createCommand(new BigDecimal("95.0"), new BigDecimal("0.90"), Instant.now()));

        // 1. GET /api/v1/alerts -> 2 aktif alarm dönmeli
        mockMvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[1].status").value("ACTIVE"));

        // 2. GET /api/v1/alerts/machines/{machineId} -> makineye ait alarmlar dönmeli
        mockMvc.perform(get("/api/v1/alerts/machines/{machineId}", testMachine.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].machineId").value(testMachine.getId().toString()))
                .andExpect(jsonPath("$[1].machineId").value(testMachine.getId().toString()));
    }
}
