package com.industrialoperations.platform.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.industrialoperations.platform.AbstractIntegrationTest;
import com.industrialoperations.platform.common.Measurements;
import com.industrialoperations.platform.machine.Machine;
import com.industrialoperations.platform.machine.MachineRepository;
import com.industrialoperations.platform.sensor.Sensor;
import com.industrialoperations.platform.sensor.SensorRepository;
import com.industrialoperations.platform.telemetry.RecordTelemetryCommand;
import com.industrialoperations.platform.telemetry.Telemetry;
import com.industrialoperations.platform.telemetry.TelemetryService;

public class MachineStateConcurrencyIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private MachineRepository machineRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private MachineLatestStateRepository machineLatestStateRepository;

    @Autowired
    TelemetryService telemetryService;

    @Test
    void versionStateTest() {
        Machine newMachine = machineRepository.save(new Machine("machine-1", "ref-machine-1"));

        Sensor sensor = sensorRepository
                .save(new Sensor("sensor-01", newMachine.getId(), "Vibration Sensor", "VIBRATION"));

        telemetryService.recordTelemetry(new RecordTelemetryCommand(
                UUID.randomUUID(),
                "msg-1",
                sensor.getSensorId(),
                newMachine.getId(),
                Instant.parse("2026-10-02T10:00:00Z"),
                Instant.now(),
                new Measurements(new BigDecimal("75.00"), new BigDecimal("0.020"))));

        MachineLatestState state = machineLatestStateRepository.findById(newMachine.getId()).orElseThrow();
        assertThat(state.getVersion()).isEqualTo(0L);

        telemetryService.recordTelemetry(new RecordTelemetryCommand(
                UUID.randomUUID(),
                "msg-2",
                sensor.getSensorId(),
                newMachine.getId(),
                Instant.parse("2026-10-02T10:05:00Z"),
                Instant.now(),
                new Measurements(new BigDecimal("75.00"), new BigDecimal("0.020"))));

        MachineLatestState updatedState = machineLatestStateRepository.findById(newMachine.getId()).orElseThrow();
        assertThat(updatedState.getVersion()).isEqualTo(1L);
    }

    @Test
    void shouldThrowOptimisticLockExceptionOnConcurrentUpdate() {
        Machine newMachine = machineRepository.save(new Machine("machine-2", "ref-machine-2"));

        Sensor newSensor = sensorRepository
                .save(new Sensor("sensor-02", newMachine.getId(), "Vibration Sensor", "VIBRATION"));

        telemetryService.recordTelemetry(new RecordTelemetryCommand(
                UUID.randomUUID(),
                "msg-1",
                newSensor.getSensorId(),
                newMachine.getId(),
                Instant.parse("2026-10-02T10:05:00Z"),
                Instant.now(),
                new Measurements(new BigDecimal("75.00"), new BigDecimal("0.020"))));

        MachineLatestState copy1 = machineLatestStateRepository.findById(newMachine.getId()).orElseThrow();
        MachineLatestState copy2 = machineLatestStateRepository.findById(newMachine.getId()).orElseThrow();

        copy1.update(copy1.getTelemetryId(),
                UUID.randomUUID(),
                "msg-2",
                newSensor.getSensorId(),
                Instant.parse("2026-10-02T10:05:00Z"),
                Instant.now(),
                new Measurements(new BigDecimal("75.00"), new BigDecimal("0.020")));

        machineLatestStateRepository.saveAndFlush(copy1);

        copy2.update(copy2.getTelemetryId(),
                UUID.randomUUID(),
                "msg-2",
                newSensor.getSensorId(),
                Instant.parse("2026-10-02T10:06:00Z"),
                Instant.now(),
                new Measurements(new BigDecimal("75.00"), new BigDecimal("0.020")));

        assertThatThrownBy(() -> machineLatestStateRepository.saveAndFlush(copy2))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    }
}
