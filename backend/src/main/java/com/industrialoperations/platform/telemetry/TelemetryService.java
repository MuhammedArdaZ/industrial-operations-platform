package com.industrialoperations.platform.telemetry;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.industrialoperations.platform.alert.AlertService;
import com.industrialoperations.platform.machine.MachineService;
import com.industrialoperations.platform.state.LatestStateUpdate;
import com.industrialoperations.platform.state.MachineStateService;

@Service
public class TelemetryService {
    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);

    private final TelemetryRepository telemetryRepository;
    private final MachineStateService machineStateService;
    private final MachineService machineService;
    private final TelemetryMetrics telemetryMetrics;
    private final AlertService alertService;

    public TelemetryService(TelemetryRepository telemetryRepository, MachineStateService machineStateService,
            MachineService machineService, TelemetryMetrics telemetryMetrics, AlertService alertService) {
        this.telemetryRepository = telemetryRepository;
        this.machineStateService = machineStateService;
        this.machineService = machineService;
        this.telemetryMetrics = telemetryMetrics;
        this.alertService = alertService;
    }

    @Transactional
    public Telemetry recordTelemetry(RecordTelemetryCommand command) {

        return telemetryMetrics.recordProcessingTime(() -> {

            if (telemetryRepository.existsBySensorIdAndSourceMessageId(command.sensorId(), command.sourceMessageId())) {
                log.info("Duplicate telemetry detected for sensorId={} and sourceMessageId={}. Skipping.",
                        command.sensorId(), command.sourceMessageId());

                telemetryMetrics.incrementDuplicateSensorMessage();

                return telemetryRepository
                        .findBySensorIdAndSourceMessageId(command.sensorId(), command.sourceMessageId())
                        .orElse(null);
            }
            if (telemetryRepository.existsByEventId(command.eventId())) {
                log.info("Duplicate telemetry detected for eventId={}. Skipping.", command.eventId());

                telemetryMetrics.incrementDuplicateEvent();

                return telemetryRepository.findByEventId(command.eventId()).orElse(null);
            }

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

            alertService.evaluateTelemetry(
                    command.machineId(),
                    command.sensorId(),
                    command.occurredAt(),
                    command.measurements().temperature(),
                    command.measurements().vibration());

            telemetryMetrics.incrementIngested();

            return saved;
        });
    }

    @Transactional(readOnly = true)
    public List<Telemetry> getTelemetryHistory(UUID machineId) {
        machineService.getMachine(machineId);

        return telemetryRepository.findByMachineIdOrderByOccurredAtDesc(machineId);
    }
}
