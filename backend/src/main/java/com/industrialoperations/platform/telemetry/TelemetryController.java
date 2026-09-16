package com.industrialoperations.platform.telemetry;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/machines/{machineId}/telemetry")
public class TelemetryController {

    private final TelemetryService telemetryService;

    public TelemetryController(TelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    @GetMapping
    public ResponseEntity<List<TelemetryResponse>> getTelemetryHistory(@PathVariable UUID machineId) {
        List<TelemetryResponse> responses = telemetryService.getTelemetryHistory(machineId)
                .stream()
                .map(telemetry -> TelemetryResponse.from(telemetry))
                .toList();

        return ResponseEntity.ok(responses);
    }
}