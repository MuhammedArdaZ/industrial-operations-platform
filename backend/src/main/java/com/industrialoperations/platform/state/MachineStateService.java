package com.industrialoperations.platform.state;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service 
public class MachineStateService {

    private final MachineLatestStateRepository machineLatestStateRepository;

    public MachineStateService(MachineLatestStateRepository machineLatestStateRepository) {
        this.machineLatestStateRepository = machineLatestStateRepository;
    }

    @Transactional
    public MachineLatestState updateLatestState(UUID machineId, UUID telemetryId, UUID eventId,
            String sourceMessageId, String sensorId,
            Instant occurredAt, Instant receivedAt,
            BigDecimal temperature, BigDecimal vibration) {

        Optional<MachineLatestState> existing = machineLatestStateRepository.findById(machineId);

        if (existing.isPresent()) {
            MachineLatestState state = existing.get();

            if (occurredAt.isAfter(state.getOccurredAt())) {
                state.update(telemetryId, eventId, sourceMessageId, sensorId, occurredAt, receivedAt, temperature,
                        vibration);
                return machineLatestStateRepository.save(state);
            }
            return state;
        } else {
            MachineLatestState newState = new MachineLatestState(machineId, telemetryId, eventId, sourceMessageId,
                    sensorId, occurredAt, receivedAt, temperature, vibration);
            return machineLatestStateRepository.save(newState);
        }
    }

    @Transactional(readOnly = true)
    public MachineLatestState getLatestState(UUID machineId) {
        return machineLatestStateRepository.findById(machineId)
                .orElseThrow(() -> new MachineStateNotFoundException(
                        "No telemetry-derived state found for machine: " + machineId));
    }
}
