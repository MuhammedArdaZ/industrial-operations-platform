package com.industrialoperations.platform.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.industrialoperations.platform.AbstractIntegrationTest;
import com.industrialoperations.platform.alert.AlertEvent;
import com.industrialoperations.platform.alert.AlertEventType;
import com.industrialoperations.platform.alert.AlertRepository;
import com.industrialoperations.platform.alert.AlertRuleType;
import com.industrialoperations.platform.alert.AlertService;
import com.industrialoperations.platform.alert.AlertStatus;
import com.industrialoperations.platform.machine.Machine;
import com.industrialoperations.platform.machine.MachineRepository;
import com.industrialoperations.platform.sensor.Sensor;
import com.industrialoperations.platform.sensor.SensorRepository;
import com.industrialoperations.platform.state.MachineLatestStateRepository;
import com.industrialoperations.platform.telemetry.TelemetryRepository;

/**
 * Verifies the AFTER_COMMIT contract of {@link AlertEventLogger}: an alert event reaches the
 * listener only once the surrounding transaction has committed, and never when it rolls back.
 *
 * <p>The production listener bean is spied on (not replaced by a test-only listener), so changing
 * its annotation to a plain {@code @EventListener} makes the rollback test fail.
 */
class AlertEventLoggerIntegrationTest extends AbstractIntegrationTest {

    private static final BigDecimal BREACHING_TEMPERATURE = new BigDecimal("95.0");
    private static final BigDecimal NORMAL_VIBRATION = new BigDecimal("0.10");

    @MockitoSpyBean
    private AlertEventLogger alertEventLogger;

    @Autowired
    private AlertService alertService;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private MachineRepository machineRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private MachineLatestStateRepository machineLatestStateRepository;

    @Autowired
    private TelemetryRepository telemetryRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Machine machine;
    private Sensor sensor;

    @BeforeEach
    void setUp() {
        cleanUp();
        machine = machineRepository.save(new Machine("Press-01", "REF-REALTIME-01"));
        sensor = sensorRepository.save(new Sensor("sensor-realtime-01", machine.getId(), "Press Sensor", "MULTI"));
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    // The PostgreSQL container is shared across test classes, so delete in foreign-key order,
    // including tables this test never writes to but other tests may have left rows in.
    private void cleanUp() {
        alertRepository.deleteAll();
        machineLatestStateRepository.deleteAll();
        telemetryRepository.deleteAll();
        sensorRepository.deleteAll();
        machineRepository.deleteAll();
    }

    @Test
    @DisplayName("Should deliver CREATED event to the listener after the transaction commits")
    void shouldDeliverCreatedEventAfterCommit() {
        alertService.evaluateTelemetry(
                machine.getId(), sensor.getSensorId(), Instant.now(), BREACHING_TEMPERATURE, NORMAL_VIBRATION);

        // AFTER_COMMIT listeners run synchronously on the committing thread, so no waiting is needed.
        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventLogger).on(captor.capture());

        AlertEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo(AlertEventType.CREATED);
        assertThat(event.machineId()).isEqualTo(machine.getId());
        assertThat(event.ruleType()).isEqualTo(AlertRuleType.HIGH_TEMPERATURE);
        assertThat(event.status()).isEqualTo(AlertStatus.ACTIVE);
        assertThat(event.currentValue()).isEqualByComparingTo(BREACHING_TEMPERATURE);
    }

    @Test
    @DisplayName("Should not deliver any event when the surrounding transaction rolls back")
    void shouldNotDeliverEventWhenTransactionRollsBack() {
        // Simulates the consumer boundary: alert evaluation joins an outer transaction, and a later
        // step in that same transaction fails (e.g. an optimistic-lock conflict on the projection).
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            alertService.evaluateTelemetry(
                    machine.getId(), sensor.getSensorId(), Instant.now(), BREACHING_TEMPERATURE, NORMAL_VIBRATION);
            throw new IllegalStateException("Simulated failure after alert evaluation");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(alertRepository.findAll()).isEmpty();
        verify(alertEventLogger, never()).on(any());
    }
}
