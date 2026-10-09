package com.industrialoperations.platform.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.industrialoperations.platform.AbstractIntegrationTest;
import com.industrialoperations.platform.machine.Machine;
import com.industrialoperations.platform.machine.MachineRepository;
import com.industrialoperations.platform.sensor.SensorRepository;
import com.industrialoperations.platform.sensor.Sensor;
import com.industrialoperations.platform.common.Measurements;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@AutoConfigureMockMvc
public class TelemetryObservabilityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TelemetryService telemetryService;

    @Autowired
    private MachineRepository machineRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Test
    void shouldExposeHealthEndpointAsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void shouldExposeCustomPrometheusMetrics() throws Exception {

        Machine machine = machineRepository.save(new Machine("CNC-OBS-01", "REF-OBS-01"));
        Sensor sensor = sensorRepository.save(
                new Sensor("sensor-obs-01", machine.getId(), "Vibration Unit", "VIBRATION"));

        UUID eventId = UUID.randomUUID();
        RecordTelemetryCommand command = new RecordTelemetryCommand(
                eventId,
                "msg-obs-100",
                sensor.getSensorId(),
                machine.getId(),
                Instant.now(),
                Instant.now(),
                new Measurements(new BigDecimal("45.2"), new BigDecimal("0.012")));

        telemetryService.recordTelemetry(command);
        telemetryService.recordTelemetry(command);

        MvcResult result = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn();

        String response = result.getResponse().getContentAsString();

        assertThat(response).contains("telemetry_ingested_total");
        assertThat(response).contains("telemetry_duplicates_total");
        assertThat(response).contains("telemetry_processing_time_seconds");
    }
}
