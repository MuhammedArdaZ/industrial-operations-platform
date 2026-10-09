package com.industrialoperations.platform.alert;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertService {

    private static final BigDecimal TEMPERATURE_THRESHOLD = BigDecimal.valueOf(90.0);
    private static final BigDecimal TEMPERATURE_CLEAR_THRESHOLD = BigDecimal.valueOf(85.0);

    private static final BigDecimal VIBRATION_THRESHOLD = BigDecimal.valueOf(0.80);
    private static final BigDecimal VIBRATION_CLEAR_THRESHOLD = BigDecimal.valueOf(0.60);

    private final AlertRepository alertRepository;

    public AlertService(AlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    @Transactional
    public void evaluateTelemetry(
            UUID machineId,
            String sensorId,
            Instant occurredAt,
            BigDecimal temperature,
            BigDecimal vibration) {

        evaluateTemperature(machineId, sensorId, occurredAt, temperature);
        evaluateVibration(machineId, sensorId, occurredAt, vibration);
    }

    private void evaluateTemperature(
            UUID machineId,
            String sensorId,
            Instant occurredAt,
            BigDecimal temperature) {

        if (temperature == null) {
            return;
        }

        if (temperature.compareTo(TEMPERATURE_THRESHOLD) >= 0) {
            Optional<Alert> activeAlert = alertRepository.findByMachineIdAndRuleTypeAndStatus(
                    machineId,
                    AlertRuleType.HIGH_TEMPERATURE,
                    AlertStatus.ACTIVE);

            if (activeAlert.isPresent()) {
                Alert alert = activeAlert.get();
                alert.updateCurrentValue(temperature);
                alertRepository.save(alert);
            } else {
                Alert newAlert = new Alert(
                        machineId,
                        sensorId,
                        AlertRuleType.HIGH_TEMPERATURE,
                        AlertSeverity.CRITICAL,
                        temperature,
                        TEMPERATURE_THRESHOLD,
                        "Critical temperature breach: " + temperature + "°C",
                        occurredAt);
                alertRepository.save(newAlert);
            }
        } else if (temperature.compareTo(TEMPERATURE_CLEAR_THRESHOLD) < 0) {
            resolveOpenAlerts(machineId, AlertRuleType.HIGH_TEMPERATURE, occurredAt);
        }
    }

    private void evaluateVibration(
            UUID machineId,
            String sensorId,
            Instant occurredAt,
            BigDecimal vibration) {

        if (vibration == null) {
            return;
        }

        if (vibration.compareTo(VIBRATION_THRESHOLD) >= 0) {
            Optional<Alert> activeAlert = alertRepository.findByMachineIdAndRuleTypeAndStatus(
                    machineId,
                    AlertRuleType.HIGH_VIBRATION,
                    AlertStatus.ACTIVE);

            if (activeAlert.isPresent()) {
                Alert alert = activeAlert.get();
                alert.updateCurrentValue(vibration);
                alertRepository.save(alert);
            } else {
                Alert newAlert = new Alert(
                        machineId,
                        sensorId,
                        AlertRuleType.HIGH_VIBRATION,
                        AlertSeverity.CRITICAL,
                        vibration,
                        VIBRATION_THRESHOLD,
                        "Critical vibration breach: " + vibration + "g",
                        occurredAt);
                alertRepository.save(newAlert);
            }
        } else if (vibration.compareTo(VIBRATION_CLEAR_THRESHOLD) < 0) {
            resolveOpenAlerts(machineId, AlertRuleType.HIGH_VIBRATION, occurredAt);
        }
    }

    private void resolveOpenAlerts(UUID machineId, AlertRuleType ruleType, Instant resolvedAt) {
        Optional<Alert> activeAlert = alertRepository.findByMachineIdAndRuleTypeAndStatus(
                machineId,
                ruleType,
                AlertStatus.ACTIVE);
        activeAlert.ifPresent(a -> {
            a.resolve(resolvedAt);
            alertRepository.save(a);
        });

        Optional<Alert> acknowledgedAlert = alertRepository.findByMachineIdAndRuleTypeAndStatus(
                machineId,
                ruleType,
                AlertStatus.ACKNOWLEDGED);
        acknowledgedAlert.ifPresent(a -> {
            a.resolve(resolvedAt);
            alertRepository.save(a);
        });
    }

    @Transactional
    public Alert acknowledgeAlert(UUID alertId) {
        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new IllegalArgumentException("Alert not found with id: " + alertId));
        alert.acknowledge(Instant.now());
        return alertRepository.save(alert);
    }

    @Transactional(readOnly = true)
    public List<Alert> getActiveAlerts() {
        return alertRepository.findByStatusOrderByTriggeredAtDesc(AlertStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<Alert> getAlertsByMachine(UUID machineId) {
        return alertRepository.findByMachineIdOrderByTriggeredAtDesc(machineId);
    }
}
