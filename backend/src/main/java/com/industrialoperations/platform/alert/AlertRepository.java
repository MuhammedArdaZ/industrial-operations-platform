package com.industrialoperations.platform.alert;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository 
public interface AlertRepository extends JpaRepository<Alert, UUID> {
    
    Optional<Alert> findByMachineIdAndRuleTypeAndStatus(UUID machineId, AlertRuleType ruleType, AlertStatus status);

    List<Alert> findByMachineIdOrderByTriggeredAtDesc(UUID machineId);

    List<Alert> findByStatusOrderByTriggeredAtDesc(AlertStatus status);
}
