package com.industrialoperations.platform.machine;

import java.time.Instant;
import java.util.UUID;

public record MachineResponse(UUID machineId, String name, String externalReference, Instant createdAt) {

    public static MachineResponse from(Machine machine){
        return new MachineResponse(
            machine.getId(), 
            machine.getName(), 
            machine.getExternalReference(), 
            machine.getCreatedAt());
    }
}
