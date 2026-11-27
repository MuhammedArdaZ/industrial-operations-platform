package com.industrialoperations.platform.alert;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AlertController.class)
class AlertControllerTest {

    private static final Instant ACKNOWLEDGED_AT = Instant.parse("2026-11-11T10:05:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AlertService alertService;

    @Test
    void shouldAcknowledgeAlertSuccessfully() throws Exception {
        Alert alert = newActiveAlert();
        alert.acknowledge(ACKNOWLEDGED_AT);
        when(alertService.acknowledgeAlert(alert.getId())).thenReturn(alert);

        mockMvc.perform(post("/api/v1/alerts/{alertId}/acknowledge", alert.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alert.getId().toString()))
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.acknowledgedAt").isNotEmpty());
    }

    @Test
    void shouldReturn404WhenAlertNotFound() throws Exception {
        UUID unknownId = UUID.randomUUID();
        when(alertService.acknowledgeAlert(unknownId))
                .thenThrow(new AlertNotFoundException(unknownId));

        mockMvc.perform(post("/api/v1/alerts/{alertId}/acknowledge", unknownId))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Alert not found with id: " + unknownId))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void shouldReturn409WithCurrentStatusWhenAlertAlreadyAcknowledged() throws Exception {
        UUID alertId = UUID.randomUUID();
        when(alertService.acknowledgeAlert(alertId))
                .thenThrow(new InvalidAlertTransitionException(alertId, AlertStatus.ACKNOWLEDGED));

        mockMvc.perform(post("/api/v1/alerts/{alertId}/acknowledge", alertId))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.currentStatus").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void shouldReturn409WhenAcknowledgeKeepsConflictingAfterRetry() throws Exception {
        UUID alertId = UUID.randomUUID();
        ObjectOptimisticLockingFailureException lockFailure =
                new ObjectOptimisticLockingFailureException(Alert.class, alertId);
        when(alertService.acknowledgeAlert(alertId))
                .thenThrow(new AlertConcurrentModificationException(alertId, lockFailure));

        mockMvc.perform(post("/api/v1/alerts/{alertId}/acknowledge", alertId))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.alertId").value(alertId.toString()))
                .andExpect(jsonPath("$.detail").value("Alert was modified concurrently. Reload it and retry."))
                // The ORM cause must not leak: its message names the entity class and the locking mechanism.
                .andExpect(jsonPath("$.detail").value(not(containsString("optimistic locking failed"))))
                .andExpect(jsonPath("$.detail").value(not(containsString(Alert.class.getName()))))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void shouldLeaveDefaultStatusFilterToServiceWhenParamAbsent() throws Exception {
        when(alertService.getOpenAlerts(isNull())).thenReturn(List.of(newActiveAlert()));

        mockMvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));

        // Absent param binds to null; the ACTIVE default is a domain rule and lives in AlertService.
        verify(alertService).getOpenAlerts(isNull());
    }

    @Test
    void shouldBindCommaSeparatedStatusFilter() throws Exception {
        Alert active = newActiveAlert();
        Alert acknowledged = newActiveAlert();
        acknowledged.acknowledge(ACKNOWLEDGED_AT);
        List<AlertStatus> requested = List.of(AlertStatus.ACTIVE, AlertStatus.ACKNOWLEDGED);
        when(alertService.getOpenAlerts(requested)).thenReturn(List.of(active, acknowledged));

        mockMvc.perform(get("/api/v1/alerts").param("status", "ACTIVE,ACKNOWLEDGED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].status", containsInAnyOrder("ACTIVE", "ACKNOWLEDGED")));

        verify(alertService).getOpenAlerts(requested);
    }

    @Test
    void shouldBindRepeatedStatusParamsLikeCommaSeparated() throws Exception {
        List<AlertStatus> requested = List.of(AlertStatus.ACTIVE, AlertStatus.ACKNOWLEDGED);
        when(alertService.getOpenAlerts(requested)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/alerts").param("status", "ACTIVE", "ACKNOWLEDGED"))
                .andExpect(status().isOk());

        verify(alertService).getOpenAlerts(requested);
    }

    @Test
    void shouldReturn400WithoutCallingServiceWhenStatusIsNotAnEnumValue() throws Exception {
        // Enum conversion fails in the web layer (MethodArgumentTypeMismatchException), before the controller runs.
        mockMvc.perform(get("/api/v1/alerts").param("status", "FOO"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(alertService);
    }

    @Test
    void shouldReturn400WithRejectedAndAllowedStatusesWhenFilterOutsideOpenSet() throws Exception {
        when(alertService.getOpenAlerts(any()))
                .thenThrow(new InvalidAlertStatusFilterException(
                        List.of(AlertStatus.RESOLVED),
                        List.of(AlertStatus.ACTIVE, AlertStatus.ACKNOWLEDGED)));

        mockMvc.perform(get("/api/v1/alerts").param("status", "ACTIVE,RESOLVED"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.rejected.length()").value(1))
                .andExpect(jsonPath("$.rejected[0]").value("RESOLVED"))
                // The client needs to know what it may send instead, otherwise the 400 is not actionable.
                .andExpect(jsonPath("$.allowed", containsInAnyOrder("ACTIVE", "ACKNOWLEDGED")))
                .andExpect(jsonPath("$.trace").doesNotExist());
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
                Instant.parse("2026-11-11T10:00:00Z"));
    }
}
