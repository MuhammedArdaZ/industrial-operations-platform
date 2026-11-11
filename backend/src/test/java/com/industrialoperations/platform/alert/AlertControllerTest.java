package com.industrialoperations.platform.alert;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
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
