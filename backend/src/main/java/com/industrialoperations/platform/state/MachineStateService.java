package com.industrialoperations.platform.state;

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
    public MachineLatestState updateLatestState(LatestStateUpdate update) {

        Optional<MachineLatestState> existing = machineLatestStateRepository.findById(update.machineId());

        if (existing.isPresent()) {
            MachineLatestState state = existing.get();

            if (update.occurredAt().isAfter(state.getOccurredAt())) {
                state.update(update.telemetryId(), update.eventId(), update.sourceMessageId(),
                        update.sensorId(), update.occurredAt(), update.receivedAt(), update.measurements());
                return machineLatestStateRepository.save(state);
            }
            return state;
        } else {
            MachineLatestState newState = new MachineLatestState(update.machineId(), update.telemetryId(),
                    update.eventId(), update.sourceMessageId(), update.sensorId(), update.occurredAt(),
                    update.receivedAt(), update.measurements());
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
