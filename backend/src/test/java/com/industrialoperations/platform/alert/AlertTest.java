package com.industrialoperations.platform.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AlertTest {

    private static final Instant TRIGGERED_AT = Instant.parse("2026-11-11T10:00:00Z");
    private static final Instant ACKNOWLEDGED_AT = Instant.parse("2026-11-11T10:05:00Z");
    private static final Instant RESOLVED_AT = Instant.parse("2026-11-11T10:10:00Z");

    @Test
    void shouldAcknowledgeActiveAlert() {
        Alert alert = newActiveAlert();

        alert.acknowledge(ACKNOWLEDGED_AT);

        assertThat(alert.getStatus()).isEqualTo(AlertStatus.ACKNOWLEDGED);
        assertThat(alert.getAcknowledgedAt()).isEqualTo(ACKNOWLEDGED_AT);
    }

    @Test
    void shouldRejectSecondAcknowledgeAndKeepOriginalTimestamp() {
        Alert alert = newActiveAlert();
        alert.acknowledge(ACKNOWLEDGED_AT);

        InvalidAlertTransitionException ex = assertThrows(
                InvalidAlertTransitionException.class,
                () -> alert.acknowledge(ACKNOWLEDGED_AT.plusSeconds(60)));

        assertThat(ex.getCurrentStatus()).isEqualTo(AlertStatus.ACKNOWLEDGED);
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.ACKNOWLEDGED);
        assertThat(alert.getAcknowledgedAt()).isEqualTo(ACKNOWLEDGED_AT);
    }

    @Test
    void shouldRejectAcknowledgeOfResolvedAlert() {
        Alert alert = newActiveAlert();
        alert.resolve(RESOLVED_AT);

        InvalidAlertTransitionException ex = assertThrows(
                InvalidAlertTransitionException.class,
                () -> alert.acknowledge(ACKNOWLEDGED_AT));

        assertThat(ex.getCurrentStatus()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(alert.getAcknowledgedAt()).isNull();
    }

    private static Alert newActiveAlert() {
        return new Alert(
                UUID.randomUUID(),
                "temp-sensor-1",
                AlertRuleType.HIGH_TEMPERATURE,
                AlertSeverity.CRITICAL,
                new BigDecimal("95.0"),
                new BigDecimal("90.0"),
                "Temperature above threshold",
                TRIGGERED_AT);
    }
}
