package com.industrialoperations.platform.sensor;

import java.util.List;
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
public class SensorController {

    private final SensorService sensorService;

    public SensorController(SensorService sensorService) {
        this.sensorService = sensorService;
    }

    @PostMapping("/{machineId}/sensors")
    public ResponseEntity<SensorResponse> createSensor(@PathVariable UUID machineId,
            @RequestBody RegisterSensorRequest request) {
        Sensor sensor = sensorService.registerSensor(machineId, request.sensorId(), request.name(), request.type());

        return ResponseEntity.status(HttpStatus.CREATED).body(SensorResponse.from(sensor));
    }

    @GetMapping("/{machineId}/sensors")
    public ResponseEntity<List<SensorResponse>> getSensors(@PathVariable UUID machineId) {
        List<SensorResponse> responses = sensorService.getSensorsByMachine(machineId)
                .stream()
                .map(sensor -> SensorResponse.from(sensor))
                .toList();
        return ResponseEntity.ok(responses);

    }

}
