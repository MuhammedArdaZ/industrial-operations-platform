package com.industrialoperations.platform.machine;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MachineController.class)
class MachineControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MachineService machineService;

    @Test
    void shouldCreateMachineSuccessfully() throws Exception {
        Machine machine = new Machine("Press-01", "EXT-01");
        when(machineService.createMachine(eq("Press-01"), eq("EXT-01"))).thenReturn(machine);

        mockMvc.perform(post("/api/v1/machines")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": "Press-01",
                        "externalReference": "EXT-01"
                    }
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Press-01"))
                .andExpect(jsonPath("$.externalReference").value("EXT-01"))
                .andExpect(jsonPath("$.machineId").isNotEmpty());
    }

    @Test
    void shouldReturn409WhenDuplicateExternalReference() throws Exception {
        when(machineService.createMachine(any(), eq("DUPLICATE-REF")))
                .thenThrow(new DuplicateExternalReferenceException("Reference in use"));

        mockMvc.perform(post("/api/v1/machines")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": "Press-02",
                        "externalReference": "DUPLICATE-REF"
                    }
                """))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldGetMachineSuccessfully() throws Exception {
        UUID machineId = UUID.randomUUID();
        Machine machine = new Machine("CNC-01", "REF-CNC");
        when(machineService.getMachine(machineId)).thenReturn(machine);

        mockMvc.perform(get("/api/v1/machines/{machineId}", machineId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("CNC-01"))
                .andExpect(jsonPath("$.externalReference").value("REF-CNC"));
    }

    @Test
    void shouldReturn404WhenMachineNotFound() throws Exception {
        UUID unknownId = UUID.randomUUID();
        when(machineService.getMachine(unknownId))
                .thenThrow(new MachineNotFoundException("Not found"));

        mockMvc.perform(get("/api/v1/machines/{machineId}", unknownId))
                .andExpect(status().isNotFound());
    }
}