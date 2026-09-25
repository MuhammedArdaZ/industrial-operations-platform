package com.industrialoperations.platform;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("postgres")
public abstract class AbstractIntegrationTest {

    // Static singleton container: started once for the entire test suite lifecycle
    protected static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
        POSTGRES.start(); // Start container and keep running across test classes
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // This base only provides PostgreSQL. Tests requiring MQTT and Kafka
        // configure their own containers and enable these settings explicitly.
        registry.add("platform.mqtt.enabled", () -> false);
        registry.add("spring.kafka.listener.auto-startup", () -> false);
    }
}