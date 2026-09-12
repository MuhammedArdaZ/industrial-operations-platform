package com.industrialoperations.platform.machine;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository 
public interface MachineRepository extends JpaRepository<Machine, UUID> {

    boolean existsByExternalReference(String externalReference);

    Optional<Machine> findByExternalReference(String externalReference);
    
}
