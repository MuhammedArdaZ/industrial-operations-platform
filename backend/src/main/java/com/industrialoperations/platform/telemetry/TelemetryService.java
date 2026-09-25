package com.industrialoperations.platform.telemetry;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.industrialoperations.platform.machine.MachineService;
import com.industrialoperations.platform.state.LatestStateUpdate;
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
    public Telemetry recordTelemetry(RecordTelemetryCommand command) {

        Telemetry telemetry = new Telemetry(command.eventId(), command.sourceMessageId(), command.sensorId(),
                command.machineId(), command.occurredAt(), command.receivedAt(), command.measurements());

        Telemetry saved = telemetryRepository.save(telemetry);

        machineStateService.updateLatestState(new LatestStateUpdate(
                command.machineId(),
                saved.getTelemetryId(),
                command.eventId(),
                command.sourceMessageId(),
                command.sensorId(),
                command.occurredAt(),
                command.receivedAt(),
                command.measurements()));

        return saved;
    }

    @Transactional(readOnly = true)
    public List<Telemetry> getTelemetryHistory(UUID machineId) {
        machineService.getMachine(machineId);

        return telemetryRepository.findByMachineIdOrderByOccurredAtDesc(machineId);
    }
}
