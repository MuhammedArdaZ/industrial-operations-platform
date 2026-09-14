package com.industrialoperations.platform.sensor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.industrialoperations.platform.machine.MachineNotFoundException;

@WebMvcTest(SensorController.class)
class SensorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SensorService sensorService;

    @Test
    void shouldRegisterSensorSuccessfully() throws Exception {
        UUID machineId = UUID.randomUUID();
        Sensor sensor = new Sensor("temp-01", machineId, "Temperature Sensor", "TEMPERATURE");
        when(sensorService.registerSensor(eq(machineId), eq("temp-01"), eq("Temperature Sensor"), eq("TEMPERATURE")))
                .thenReturn(sensor);

        mockMvc.perform(post("/api/v1/machines/{machineId}/sensors", machineId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "sensorId": "temp-01",
                        "name": "Temperature Sensor",
                        "type": "TEMPERATURE"
                    }
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sensorId").value("temp-01"))
                .andExpect(jsonPath("$.machineId").value(machineId.toString()))
                .andExpect(jsonPath("$.name").value("Temperature Sensor"))
                .andExpect(jsonPath("$.type").value("TEMPERATURE"));
    }

    @Test
    void shouldReturn404WhenMachineNotFoundDuringRegistration() throws Exception {
        UUID unknownMachineId = UUID.randomUUID();
        when(sensorService.registerSensor(eq(unknownMachineId), any(), any(), any()))
                .thenThrow(new MachineNotFoundException("Machine not found"));

        mockMvc.perform(post("/api/v1/machines/{machineId}/sensors", unknownMachineId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "sensorId": "temp-01",
                        "name": "Temperature Sensor",
                        "type": "TEMPERATURE"
                    }
                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturn409WhenDuplicateSensorId() throws Exception {
        UUID machineId = UUID.randomUUID();
        when(sensorService.registerSensor(eq(machineId), eq("temp-01"), any(), any()))
                .thenThrow(new DuplicateSensorException("Sensor already exists"));

        mockMvc.perform(post("/api/v1/machines/{machineId}/sensors", machineId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "sensorId": "temp-01",
                        "name": "Temperature Sensor",
                        "type": "TEMPERATURE"
                    }
                """))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldGetSensorsByMachineSuccessfully() throws Exception {
        UUID machineId = UUID.randomUUID();
        Sensor s1 = new Sensor("temp-01", machineId, "Temp Sensor", "TEMPERATURE");
        Sensor s2 = new Sensor("vib-01", machineId, "Vibration Sensor", "VIBRATION");
        when(sensorService.getSensorsByMachine(machineId)).thenReturn(List.of(s1, s2));

        mockMvc.perform(get("/api/v1/machines/{machineId}/sensors", machineId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sensorId").value("temp-01"))
                .andExpect(jsonPath("$[1].sensorId").value("vib-01"));
    }

    @Test
    void shouldReturn404WhenGettingSensorsForUnknownMachine() throws Exception {
        UUID unknownMachineId = UUID.randomUUID();
        when(sensorService.getSensorsByMachine(unknownMachineId))
                .thenThrow(new MachineNotFoundException("Machine not found"));

        mockMvc.perform(get("/api/v1/machines/{machineId}/sensors", unknownMachineId))
                .andExpect(status().isNotFound());
    }
}