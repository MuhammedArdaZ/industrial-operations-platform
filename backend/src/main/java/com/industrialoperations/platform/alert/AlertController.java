package com.industrialoperations.platform.alert;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping
    public ResponseEntity<List<AlertResponse>> getAlerts() {
        List<AlertResponse> alerts = alertService.getActiveAlerts()
                .stream()
                .map(AlertResponse::from)
                .toList();

        return ResponseEntity.ok(alerts);
    }

    @GetMapping("/machines/{machineId}")
    public ResponseEntity<List<AlertResponse>> getAlertsByMachine(@PathVariable UUID machineId) {
        List<AlertResponse> alerts = alertService.getAlertsByMachine(machineId)
                .stream()
                .map(AlertResponse::from)
                .toList();

        return ResponseEntity.ok(alerts);
    }

    @PostMapping("/{alertId}/acknowledge")
    public ResponseEntity<AlertResponse> postAcknowledgeAlert(@PathVariable UUID alertId){
        Alert alertAcknowledged = alertService.acknowledgeAlert(alertId);

        return ResponseEntity.ok(AlertResponse.from(alertAcknowledged));
    }

}
