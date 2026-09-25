package com.industrialoperations.platform.state;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.industrialoperations.platform.common.Measurements;

@WebMvcTest(MachineStateController.class)
class MachineStateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MachineStateService machineStateService;

    @Test
    void shouldReturnLatestStateSuccessfully() throws Exception {
        UUID machineId = UUID.randomUUID();
        UUID telemetryId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        MachineLatestState state = new MachineLatestState(
                machineId,
                telemetryId,
                eventId,
                "msg-state-1",
                "SENSOR-01",
                Instant.parse("2026-09-16T12:00:00Z"),
                Instant.parse("2026-09-16T12:00:01Z"),
                new Measurements(new BigDecimal("68.40"), new BigDecimal("0.021")));

        when(machineStateService.getLatestState(machineId)).thenReturn(state);

        mockMvc.perform(get("/api/v1/machines/{machineId}/latest-state", machineId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machineId").value(machineId.toString()))
                .andExpect(jsonPath("$.telemetryId").value(telemetryId.toString()))
                .andExpect(jsonPath("$.sensorId").value("SENSOR-01"))
                .andExpect(jsonPath("$.temperature").value(68.40))
                .andExpect(jsonPath("$.vibration").value(0.021));
    }

    @Test
    void shouldReturn404WhenStateNotFound() throws Exception {
        UUID unknownId = UUID.randomUUID();
        when(machineStateService.getLatestState(unknownId))
                .thenThrow(new MachineStateNotFoundException("No state found"));

        mockMvc.perform(get("/api/v1/machines/{machineId}/latest-state", unknownId))
                .andExpect(status().isNotFound());
    }
}
