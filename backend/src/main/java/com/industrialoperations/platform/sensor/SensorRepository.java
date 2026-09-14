package com.industrialoperations.platform.sensor;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository 
public interface SensorRepository extends JpaRepository<Sensor, String>{

    public List<Sensor> findByMachineId(UUID machineId);

}
