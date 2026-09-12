package com.industrialoperations.platform.machine;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController 
@RequestMapping("/api/v1/machines")
public class MachineController {
    
    private final MachineService machineService;

    public MachineController(MachineService machineService){
        this.machineService = machineService;
    }

    @PostMapping
    public ResponseEntity<MachineResponse> createMachine(@RequestBody CreateMachineRequest request){
        Machine machine = machineService.createMachine(request.name(), request.externalReference());

        return ResponseEntity.status(HttpStatus.CREATED).body(MachineResponse.from(machine));
    }

    @GetMapping("/{machineId}")
    public ResponseEntity<MachineResponse> getMachine(@PathVariable UUID machineId){
        Machine machine = machineService.getMachine(machineId);

        return ResponseEntity.ok(MachineResponse.from(machine));
    }

}
