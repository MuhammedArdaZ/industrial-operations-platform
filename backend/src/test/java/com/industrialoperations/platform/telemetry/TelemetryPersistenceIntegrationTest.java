package com.industrialoperations.platform.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.industrialoperations.platform.AbstractIntegrationTest;
import com.industrialoperations.platform.common.Measurements;
import com.industrialoperations.platform.machine.Machine;
import com.industrialoperations.platform.machine.MachineRepository;
import com.industrialoperations.platform.sensor.Sensor;
import com.industrialoperations.platform.sensor.SensorRepository;
import com.industrialoperations.platform.state.MachineLatestState;
import com.industrialoperations.platform.state.MachineLatestStateRepository;

class TelemetryPersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MachineRepository machineRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private TelemetryRepository telemetryRepository;

    @Autowired
    private MachineLatestStateRepository machineLatestStateRepository;

    @Autowired
    private TelemetryService telemetryService;

    @Test
    void shouldRecordTelemetryAndUpsertLatestState() {
        Machine machine = machineRepository.save(new Machine("Press-10", "REF-PRESS-10"));
        Sensor sensor = sensorRepository.save(
                new Sensor("unit-press-10", machine.getId(), "Press-10 Sensor Unit", "MULTI_MEASUREMENT"));

        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-09-16T12:00:00Z");
        Instant receivedAt = Instant.parse("2026-09-16T12:00:01Z");
        BigDecimal temp = new BigDecimal("85.50");
        BigDecimal vib = new BigDecimal("0.042");

        Telemetry recorded = telemetryService.recordTelemetry(new RecordTelemetryCommand(
                eventId,
                "msg-101",
                sensor.getSensorId(),
                machine.getId(),
                occurredAt,
                receivedAt,
                new Measurements(temp, vib)));

        Optional<Telemetry> foundTelemetry = telemetryRepository.findById(recorded.getTelemetryId());
        assertThat(foundTelemetry).isPresent();
        assertThat(foundTelemetry.get().getMachineId()).isEqualTo(machine.getId());
        assertThat(foundTelemetry.get().getSensorId()).isEqualTo(sensor.getSensorId());
        assertThat(foundTelemetry.get().getTemperature()).isEqualByComparingTo(temp);
        assertThat(foundTelemetry.get().getVibration()).isEqualByComparingTo(vib);

        Optional<MachineLatestState> foundState = machineLatestStateRepository.findById(machine.getId());
        assertThat(foundState).isPresent();
        assertThat(foundState.get().getTelemetryId()).isEqualTo(recorded.getTelemetryId());
        assertThat(foundState.get().getTemperature()).isEqualByComparingTo(temp);
        assertThat(foundState.get().getVibration()).isEqualByComparingTo(vib);
    }

    @Test
    void shouldIgnoreOutOfOrderTelemetryWhenUpdatingLatestState() {
        Machine machine = machineRepository.save(new Machine("Press-11", "REF-PRESS-11"));
        Sensor sensor = sensorRepository.save(
                new Sensor("unit-press-11", machine.getId(), "Press-11 Sensor Unit", "MULTI_MEASUREMENT"));

        Instant timeNew = Instant.parse("2026-09-16T14:00:00Z");
        Instant timeOld = Instant.parse("2026-09-16T13:00:00Z");

        Telemetry newer = telemetryService.recordTelemetry(new RecordTelemetryCommand(
                UUID.randomUUID(),
                "msg-newer",
                sensor.getSensorId(),
                machine.getId(),
                timeNew,
                Instant.now(),
                new Measurements(new BigDecimal("90.00"), new BigDecimal("0.050"))));

        Telemetry older = telemetryService.recordTelemetry(new RecordTelemetryCommand(
                UUID.randomUUID(),
                "msg-older",
                sensor.getSensorId(),
                machine.getId(),
                timeOld,
                Instant.now(),
                new Measurements(new BigDecimal("60.00"), new BigDecimal("0.010"))));

        List<Telemetry> history = telemetryRepository.findByMachineIdOrderByOccurredAtDesc(machine.getId());
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getTelemetryId()).isEqualTo(newer.getTelemetryId());
        assertThat(history.get(1).getTelemetryId()).isEqualTo(older.getTelemetryId());

        MachineLatestState state = machineLatestStateRepository.findById(machine.getId()).orElseThrow();
        assertThat(state.getTelemetryId()).isEqualTo(newer.getTelemetryId());
        assertThat(state.getTemperature()).isEqualByComparingTo(new BigDecimal("90.00"));
        assertThat(state.getOccurredAt()).isEqualTo(timeNew);
    }
}
