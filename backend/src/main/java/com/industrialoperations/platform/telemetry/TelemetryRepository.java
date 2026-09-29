package com.industrialoperations.platform.telemetry;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository 
public interface TelemetryRepository extends JpaRepository<Telemetry, UUID>{
    
    List<Telemetry> findByMachineIdOrderByOccurredAtDesc(UUID machineId);

    boolean existsBySensorIdAndSourceMessageId(String sensorId, String sourceMessageId);

    boolean existsByEventId(UUID eventId);

    java.util.Optional<Telemetry> findBySensorIdAndSourceMessageId(String sensorId, String sourceMessageId);

    java.util.Optional<Telemetry> findByEventId(UUID eventId);
}
