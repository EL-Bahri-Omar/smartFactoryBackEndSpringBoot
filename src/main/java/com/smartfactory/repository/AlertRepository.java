package com.smartfactory.repository;

import com.smartfactory.entity.Alert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlertRepository extends MongoRepository<Alert, String> {

    List<Alert> findByMachineId(String machineId);

    Page<Alert> findByMachineId(String machineId, Pageable pageable);

    List<Alert> findByStatus(String status);

    Page<Alert> findByStatus(String status, Pageable pageable);

    Page<Alert> findBySeverity(String severity, Pageable pageable);

    Page<Alert> findByMachineIdAndStatus(String machineId, String status, Pageable pageable);

    Page<Alert> findByMachineIdAndSeverity(String machineId, String severity, Pageable pageable);

    Page<Alert> findByMachineIdAndSeverityAndStatus(String machineId, String severity, String status, Pageable pageable);

    java.util.Optional<Alert> findFirstByMachineIdAndSensorTypeAndStatus(String machineId, com.smartfactory.entity.SensorType sensorType, String status);

    java.util.Optional<Alert> findFirstByMachineIdAndSensorTypeAndStatusOrderByTimestampDesc(String machineId, com.smartfactory.entity.SensorType sensorType, String status);

    List<Alert> findByMachineIdAndSensorTypeAndStatus(String machineId, com.smartfactory.entity.SensorType sensorType, String status);

    java.util.Optional<Alert> findFirstByAnomalyId(String anomalyId);

    List<Alert> findByMachineIdAndStatus(String machineId, String status);

    long countByStatus(String status);

    long countByMachineIdAndStatus(String machineId, String status);

    long countBySeverityAndStatus(String severity, String status);

    void deleteByMachineId(String machineId);
}
