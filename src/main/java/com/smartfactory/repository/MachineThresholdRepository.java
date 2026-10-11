package com.smartfactory.repository;

import com.smartfactory.entity.MachineThreshold;
import com.smartfactory.entity.SensorType;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MachineThresholdRepository extends MongoRepository<MachineThreshold, String> {

    Optional<MachineThreshold> findByMachineIdAndSensorType(String machineId, SensorType sensorType);

    List<MachineThreshold> findByMachineId(String machineId);

    List<MachineThreshold> findByMachineCode(String machineCode);

    boolean existsByMachineIdAndSensorType(String machineId, SensorType sensorType);

    void deleteByMachineIdAndSensorType(String machineId, SensorType sensorType);

    void deleteByMachineId(String machineId);
}
