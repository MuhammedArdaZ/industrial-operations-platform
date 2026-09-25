package com.industrialoperations.platform.sensor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.industrialoperations.platform.machine.MachineService;

@Service
public class SensorService {

    private final SensorRepository sensorRepository;
    private final MachineService machineService;

    public SensorService(SensorRepository sensorRepository, MachineService machineService) {
        this.sensorRepository = sensorRepository;
        this.machineService = machineService;
    }

    @Transactional
    public Sensor registerSensor(UUID machineId, String sensorId, String name, String type) {

        machineService.getMachine(machineId); 

        if (sensorRepository.existsById(sensorId)) {
            throw new DuplicateSensorException("Sensor already exists with id: " + sensorId);
        }

        Sensor sensor = new Sensor(sensorId, machineId, name, type);
        return sensorRepository.save(sensor);

    }

    @Transactional(readOnly = true)
    public List<Sensor> getSensorsByMachine(UUID machineId) {

        machineService.getMachine(machineId);
        return sensorRepository.findByMachineId(machineId);

    }

    @Transactional(readOnly = true)
    public Optional<Sensor> findSensor(String sensorId){
        return sensorRepository.findById(sensorId);
    }

}
