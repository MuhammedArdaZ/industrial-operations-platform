package com.industrialoperations.platform.machine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


@SpringBootTest
@ActiveProfiles("postgres")
@Testcontainers
class MachinePersistenceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MachineRepository machineRepository;

    @Test
    void shouldSaveAndRetrieveMachine() {
        // 1. Hazırlık (Arrange)
        Machine machine = new Machine("Press Machine 01", "EXT-PRESS-01");

        // 2. İşlem (Act)
        Machine saved = machineRepository.save(machine);
        Optional<Machine> found = machineRepository.findById(saved.getId());

        // 3. Doğrulama (Assert)
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