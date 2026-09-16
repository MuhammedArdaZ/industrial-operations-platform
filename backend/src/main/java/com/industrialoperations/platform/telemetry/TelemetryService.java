package com.industrialoperations.platform.telemetry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.industrialoperations.platform.machine.MachineService;
import com.industrialoperations.platform.state.MachineStateService;

@Service
public class TelemetryService {
    private final TelemetryRepository telemetryRepository;
    private final MachineStateService machineStateService;
    private final MachineService machineService;

    public TelemetryService(TelemetryRepository telemetryRepository, MachineStateService machineStateService,
            MachineService machineService) {
        this.telemetryRepository = telemetryRepository;
        this.machineStateService = machineStateService;
        this.machineService = machineService;
    }

    @Transactional
    public Telemetry recordTelemetry(UUID eventId, String sourceMessageId, String sensorId, UUID machineId,
            Instant occurredAt, Instant receivedAt, BigDecimal temperature, BigDecimal vibration) {

        Telemetry telemetry = new Telemetry(eventId, sourceMessageId, sensorId, machineId, occurredAt, receivedAt,
                temperature, vibration);

        Telemetry saved = telemetryRepository.save(telemetry);

        machineStateService.updateLatestState(machineId, saved.getTelemetryId(), eventId, sourceMessageId, sensorId, occurredAt,
                receivedAt, temperature, vibration);

        return saved;
    }

    @Transactional(readOnly = true)
    public List<Telemetry> getTelemetryHistory(UUID machineId) {
        machineService.getMachine(machineId);

        return telemetryRepository.findByMachineIdOrderByOccurredAtDesc(machineId);
    }
}
