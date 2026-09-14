package com.industrialoperations.platform.sensor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.industrialoperations.platform.AbstractIntegrationTest;
import com.industrialoperations.platform.machine.Machine;
import com.industrialoperations.platform.machine.MachineRepository;

class SensorPersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MachineRepository machineRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Test
    void shouldSaveAndRetrieveSensor() {
        Machine machine = machineRepository.save(new Machine("Press-01", "REF-PRESS-01"));
        Sensor sensor = new Sensor("temp-press-01", machine.getId(), "Temperature Sensor", "TEMPERATURE");
        Sensor saved = sensorRepository.save(sensor);

        Optional<Sensor> found = sensorRepository.findById(saved.getSensorId());
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Temperature Sensor");
        assertThat(found.get().getMachineId()).isEqualTo(machine.getId());
        assertThat(found.get().getType()).isEqualTo("TEMPERATURE");
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    void shouldFindSensorsByMachineId() {
        Machine machine = machineRepository.save(new Machine("CNC-01", "REF-CNC-01"));
        sensorRepository.save(new Sensor("temp-cnc-01", machine.getId(), "Temp Sensor", "TEMPERATURE"));
        sensorRepository.save(new Sensor("vib-cnc-01", machine.getId(), "Vib Sensor", "VIBRATION"));

        List<Sensor> sensors = sensorRepository.findByMachineId(machine.getId());

        assertThat(sensors).hasSize(2);
        assertThat(sensors).extracting(Sensor::getSensorId).containsExactlyInAnyOrder("temp-cnc-01", "vib-cnc-01");
    }
}