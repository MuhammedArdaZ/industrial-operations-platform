package com.industrialoperations.platform.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.industrialoperations.platform.AbstractIntegrationTest;
import com.industrialoperations.platform.machine.Machine;
import com.industrialoperations.platform.machine.MachineRepository;
import com.industrialoperations.platform.realtime.AlertEventLogger;
import com.industrialoperations.platform.sensor.Sensor;
import com.industrialoperations.platform.sensor.SensorRepository;
import com.industrialoperations.platform.state.MachineLatestStateRepository;
import com.industrialoperations.platform.telemetry.TelemetryRepository;

/**
 * Verifies that {@link AlertService#acknowledgeAlert(UUID)} survives a real optimistic-lock
 * conflict by retrying in a fresh transaction.
 *
 * <p>The conflict is real, not simulated with {@code doThrow}: right before the acknowledge
 * attempt flushes its UPDATE, a separate committed transaction bumps the row's version, exactly
 * like the Kafka consumer or a second operator would. A faked exception would leave the database
 * row unchanged, so a broken retry that reuses the stale entity would still pass.
 */
class AlertAcknowledgeRetryIntegrationTest extends AbstractIntegrationTest {

    private static final BigDecimal BREACHING_TEMPERATURE = new BigDecimal("95.0");
    private static final BigDecimal CONCURRENT_TEMPERATURE = new BigDecimal("97.5");
    private static final BigDecimal NORMAL_VIBRATION = new BigDecimal("0.10");

    // AlertRepository is a JDK proxy, so Spring's spy delegates to the real repository instead of
    // subclassing it. Stubbed calls must therefore delegate explicitly (see callRealRepository).
    @MockitoSpyBean
    private AlertRepository alertRepository;

    @MockitoSpyBean
    private AlertEventLogger alertEventLogger;

    @Autowired
    private AlertService alertService;

    @Autowired
    private MachineRepository machineRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private MachineLatestStateRepository machineLatestStateRepository;

    @Autowired
    private TelemetryRepository telemetryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate requiresNewTransaction;
    private UUID alertId;

    @BeforeEach
    void setUp() {
        cleanUp();

        // A dedicated template: the shared TransactionTemplate bean must keep its REQUIRED default.
        requiresNewTransaction = new TransactionTemplate(transactionManager);
        requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        Machine machine = machineRepository.save(new Machine("Press-01", "REF-RETRY-01"));
        Sensor sensor = sensorRepository.save(
                new Sensor("sensor-retry-01", machine.getId(), "Press Sensor", "MULTI"));

        alertService.evaluateTelemetry(
                machine.getId(), sensor.getSensorId(), Instant.now(), BREACHING_TEMPERATURE, NORMAL_VIBRATION);
        alertId = alertRepository.findAll().getFirst().getId();

        // Setup itself called saveAndFlush and published CREATED; only count what the test does.
        clearInvocations(alertRepository, alertEventLogger);
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

    @Test
    @DisplayName("Should acknowledge on retry when the consumer updates the alert mid-attempt")
    void shouldAcknowledgeOnRetryWhenConsumerUpdatesAlertConcurrently() {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                commitConcurrentConsumerUpdate();
            }
            return callRealRepository(invocation);
        }).when(alertRepository).saveAndFlush(any());

        Alert acknowledged = alertService.acknowledgeAlert(alertId);

        assertThat(acknowledged.getStatus()).isEqualTo(AlertStatus.ACKNOWLEDGED);

        Alert stored = alertRepository.findById(alertId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AlertStatus.ACKNOWLEDGED);
        // 0 on create, 1 from the concurrent consumer write, 2 from the successful retry.
        assertThat(stored.getVersion()).isEqualTo(2L);
        // The retry re-read the row, so the consumer's value survived (no lost update).
        assertThat(stored.getCurrentValue()).isEqualByComparingTo(CONCURRENT_TEMPERATURE);

        verify(alertRepository, times(2)).saveAndFlush(any());

        // Exactly one ACKNOWLEDGED event: the failed first attempt rolled back and published nothing.
        assertThat(capturedEventsOfType(AlertEventType.ACKNOWLEDGED)).hasSize(1);
    }

    @Test
    @DisplayName("Should give up with AlertConcurrentModificationException when every attempt conflicts")
    void shouldThrowConcurrentModificationWhenEveryAttemptConflicts() {
        doAnswer(invocation -> {
            commitConcurrentConsumerUpdate();
            return callRealRepository(invocation);
        }).when(alertRepository).saveAndFlush(any());

        assertThatThrownBy(() -> alertService.acknowledgeAlert(alertId))
                .isInstanceOf(AlertConcurrentModificationException.class)
                .hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);

        Alert stored = alertRepository.findById(alertId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AlertStatus.ACTIVE);
        // Two concurrent consumer writes, zero successful acknowledge writes.
        assertThat(stored.getVersion()).isEqualTo(2L);

        verify(alertRepository, times(2)).saveAndFlush(any());
        verify(alertEventLogger, Mockito.never()).on(any());
    }

    @Test
    @DisplayName("Should reject the retry as an invalid transition when another operator acknowledged first")
    void shouldRejectRetryWhenAnotherOperatorAcknowledgedFirst() {
        doAnswer(invocation -> {
            commitConcurrentAcknowledge();
            return callRealRepository(invocation);
        }).when(alertRepository).saveAndFlush(any());

        assertThatThrownBy(() -> alertService.acknowledgeAlert(alertId))
                .isInstanceOfSatisfying(InvalidAlertTransitionException.class,
                        ex -> assertThat(ex.getCurrentStatus()).isEqualTo(AlertStatus.ACKNOWLEDGED));

        // The retry re-read the row, saw ACKNOWLEDGED and failed in Alert.acknowledge, before saving.
        verify(alertRepository, times(1)).saveAndFlush(any());
        verify(alertRepository, atLeast(2)).findById(alertId);
        verify(alertEventLogger, Mockito.never()).on(any());
    }

    /**
     * Commits a consumer-style write in its own transaction while the acknowledge attempt is still
     * open. Plain SQL bypasses {@code @Version}, so the version bump has to be written by hand.
     */
    private void commitConcurrentConsumerUpdate() {
        requiresNewTransaction.executeWithoutResult(status -> jdbcTemplate.update(
                "UPDATE alerts SET current_value = ?, version = version + 1 WHERE id = ?",
                CONCURRENT_TEMPERATURE, alertId));
    }

    /** Same as above, but simulates a second operator whose acknowledge commits first. */
    private void commitConcurrentAcknowledge() {
        requiresNewTransaction.executeWithoutResult(status -> jdbcTemplate.update(
                "UPDATE alerts SET status = 'ACKNOWLEDGED', acknowledged_at = now(), version = version + 1"
                        + " WHERE id = ?",
                alertId));
    }

    /**
     * Forwards a stubbed call to the real repository through the spy's default answer, which
     * Spring configured as {@code delegatesTo(realRepositoryProxy)}. {@code callRealMethod()}
     * would fail here because the spied type is an interface with no real method body.
     */
    private Object callRealRepository(InvocationOnMock invocation) throws Throwable {
        return Mockito.mockingDetails(alertRepository)
                .getMockCreationSettings()
                .getDefaultAnswer()
                .answer(invocation);
    }

    private List<AlertEvent> capturedEventsOfType(AlertEventType type) {
        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventLogger, atLeast(0)).on(captor.capture());
        return captor.getAllValues().stream()
                .filter(event -> event.eventType() == type)
                .toList();
    }
}
