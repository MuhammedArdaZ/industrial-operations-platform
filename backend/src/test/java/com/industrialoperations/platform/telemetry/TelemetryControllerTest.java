package com.industrialoperations.platform.telemetry;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.industrialoperations.platform.common.Measurements;

import com.industrialoperations.platform.machine.MachineNotFoundException;

@WebMvcTest(TelemetryController.class)
class TelemetryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TelemetryService telemetryService;

    @Test
    void shouldReturnTelemetryHistorySuccessfully() throws Exception {
        UUID machineId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Telemetry telemetry = new Telemetry(
                eventId,
                "msg-123",
                "SENSOR-01",
                machineId,
                Instant.parse("2026-09-16T12:00:00Z"),
                Instant.parse("2026-09-16T12:00:01Z"),
                new Measurements(new BigDecimal("72.50"), new BigDecimal("0.015")));

        when(telemetryService.getTelemetryHistory(machineId)).thenReturn(List.of(telemetry));

        mockMvc.perform(get("/api/v1/machines/{machineId}/telemetry", machineId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].machineId").value(machineId.toString()))
                .andExpect(jsonPath("$[0].sensorId").value("SENSOR-01"))
                .andExpect(jsonPath("$[0].temperature").value(72.50))
                .andExpect(jsonPath("$[0].vibration").value(0.015))
                .andExpect(jsonPath("$[0].sourceMessageId").value("msg-123"));
    }

    @Test
    void shouldReturn404WhenMachineNotFound() throws Exception {
        UUID unknownId = UUID.randomUUID();
        when(telemetryService.getTelemetryHistory(unknownId))
                .thenThrow(new MachineNotFoundException("Machine not found"));

        mockMvc.perform(get("/api/v1/machines/{machineId}/telemetry", unknownId))
                .andExpect(status().isNotFound());
    }
}
