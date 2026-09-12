package com.industrialoperations.platform.machine;

import java.util.UUID;

import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

@Service
public class MachineService {
    private final MachineRepository machineRepository;

    public MachineService(MachineRepository machineRepository) {
        this.machineRepository = machineRepository;
    }

    @Transactional
    public Machine createMachine(String name, String externalReference) {
        if (externalReference != null && !externalReference.isBlank()) {
            if (machineRepository.existsByExternalReference(externalReference)) {
                throw new DuplicateExternalReferenceException("External reference already in use: " + externalReference);
            }
        }
        Machine machine = new Machine(name, externalReference);
        return machineRepository.save(machine);
    }

    @Transactional(readOnly = true)
    public Machine getMachine(UUID id) {
        return machineRepository.findById(id)
                .orElseThrow(() -> new MachineNotFoundException("Machine not found with id: " + id));
    }
}
