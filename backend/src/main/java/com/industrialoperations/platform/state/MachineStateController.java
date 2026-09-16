package com.industrialoperations.platform.state;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/machines/{machineId}/latest-state")
public class MachineStateController {
    private final MachineStateService machineStateService;

    public MachineStateController(MachineStateService machineStateService){
        this.machineStateService = machineStateService;
    }

    @GetMapping
    public ResponseEntity<MachineLatestStateResponse> getLatestState(@PathVariable UUID machineId){
        MachineLatestState machineLatestState = machineStateService.getLatestState(machineId);

        return ResponseEntity.ok(MachineLatestStateResponse.from(machineLatestState));
    }
}
