package com.industrialoperations.platform.machine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.industrialoperations.platform.AbstractIntegrationTest;

class MachinePersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MachineRepository machineRepository;

    @Test
    void shouldSaveAndRetrieveMachine() {
        Machine machine = new Machine("Press Machine 01", "EXT-PRESS-01");
        Machine saved = machineRepository.save(machine);
        Optional<Machine> found = machineRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Press Machine 01");
        assertThat(found.get().getExternalReference()).isEqualTo("EXT-PRESS-01");
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    void shouldCheckExternalReferenceExistence() {
        Machine machine = new Machine("CNC Mill 02", "EXT-CNC-02");
        machineRepository.save(machine);

        assertThat(machineRepository.existsByExternalReference("EXT-CNC-02")).isTrue();
        assertThat(machineRepository.existsByExternalReference("NON-EXISTENT")).isFalse();
    }
}