package com.industrialoperations.platform.alert;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AlertService {
    private static final int MAX_TRANSACTION_ATTEMPTS = 2;

    private static final BigDecimal TEMPERATURE_THRESHOLD = BigDecimal.valueOf(90.0);
    private static final BigDecimal TEMPERATURE_CLEAR_THRESHOLD = BigDecimal.valueOf(85.0);

    private static final BigDecimal VIBRATION_THRESHOLD = BigDecimal.valueOf(0.80);
    private static final BigDecimal VIBRATION_CLEAR_THRESHOLD = BigDecimal.valueOf(0.60);

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final List<AlertStatus> OPEN_STATUSES = List.of(AlertStatus.ACTIVE, AlertStatus.ACKNOWLEDGED);

    private final ApplicationEventPublisher publisher;
    private final AlertRepository alertRepository;

    private final TransactionTemplate transactionTemplate;

    public AlertService(AlertRepository alertRepository, ApplicationEventPublisher publisher,
            TransactionTemplate transactionTemplate) {
        this.alertRepository = alertRepository;
        this.publisher = publisher;
        this.transactionTemplate = transactionTemplate;
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
            Optional<Alert> openAlert = alertRepository.findByMachineIdAndRuleTypeAndStatusIn(
                    machineId,
                    AlertRuleType.HIGH_TEMPERATURE,
                    OPEN_STATUSES);

            if (openAlert.isPresent()) {
                Alert alert = openAlert.get();
                alert.updateCurrentValue(temperature);
                Alert saved = alertRepository.saveAndFlush(alert);
                publisher.publishEvent(AlertEvent.from(saved, AlertEventType.UPDATED));
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
                Alert saved = alertRepository.saveAndFlush(newAlert);
                publisher.publishEvent(AlertEvent.from(saved, AlertEventType.CREATED));
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
            Optional<Alert> openAlert = alertRepository.findByMachineIdAndRuleTypeAndStatusIn(
                    machineId,
                    AlertRuleType.HIGH_VIBRATION,
                    OPEN_STATUSES);

            if (openAlert.isPresent()) {
                Alert alert = openAlert.get();
                alert.updateCurrentValue(vibration);
                Alert saved = alertRepository.saveAndFlush(alert);
                publisher.publishEvent(AlertEvent.from(saved, AlertEventType.UPDATED));
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
                Alert saved = alertRepository.saveAndFlush(newAlert);
                publisher.publishEvent(AlertEvent.from(saved, AlertEventType.CREATED));
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
            Alert saved = alertRepository.saveAndFlush(a);
            publisher.publishEvent(AlertEvent.from(saved, AlertEventType.RESOLVED));
        });

        Optional<Alert> acknowledgedAlert = alertRepository.findByMachineIdAndRuleTypeAndStatus(
                machineId,
                ruleType,
                AlertStatus.ACKNOWLEDGED);
        acknowledgedAlert.ifPresent(a -> {
            a.resolve(resolvedAt);
            Alert saved = alertRepository.saveAndFlush(a);
            publisher.publishEvent(AlertEvent.from(saved, AlertEventType.RESOLVED));
        });
    }

    public Alert acknowledgeAlert(UUID alertId) {
        for (int attempt = 1; ; attempt++) {
            try {
                return acknowledgeInNewTransaction(alertId);
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt >= MAX_TRANSACTION_ATTEMPTS) {
                    throw new AlertConcurrentModificationException(alertId, e);
                }
                log.warn("Optimistic lock conflict on acknowledge, retrying alertId={} attempt={} ", alertId, attempt);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<Alert> getActiveAlerts() {
        return alertRepository.findByStatusOrderByTriggeredAtDesc(AlertStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<Alert> getAlertsByMachine(UUID machineId) {
        return alertRepository.findByMachineIdOrderByTriggeredAtDesc(machineId);
    }

    private Alert acknowledgeInNewTransaction(UUID alertId) {
        return transactionTemplate.execute(status -> {
            Alert alert = alertRepository.findById(alertId)
                    .orElseThrow(() -> new AlertNotFoundException(alertId));
            alert.acknowledge(Instant.now());
            Alert saved = alertRepository.saveAndFlush(alert);
            publisher.publishEvent(AlertEvent.from(saved, AlertEventType.ACKNOWLEDGED));
            return saved;
        });
    }
}
