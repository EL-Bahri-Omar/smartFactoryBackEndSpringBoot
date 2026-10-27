package com.smartfactory.repository;

import com.smartfactory.entity.Anomaly;
import com.smartfactory.entity.SensorType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AnomalyRepository extends MongoRepository<Anomaly, String> {

    List<Anomaly> findByMachineId(String machineId);

    Page<Anomaly> findByMachineId(String machineId, Pageable pageable);

    List<Anomaly> findByStatus(String status);

    Page<Anomaly> findByStatus(String status, Pageable pageable);

    Optional<Anomaly> findFirstByMachineIdAndSensorTypeAndStatus(String machineId, SensorType sensorType, String status);

    Optional<Anomaly> findFirstByMachineIdAndSensorTypeAndStatusIn(String machineId, SensorType sensorType, Collection<String> statuses);

    Page<Anomaly> findByMachineIdAndStatus(String machineId, String status, Pageable pageable);

    Page<Anomaly> findByMachineIdAndTimestampBetween(String machineId, Instant from, Instant to, Pageable pageable);

    List<Anomaly> findByMachineIdAndTimestampBetween(String machineId, Instant from, Instant to);

    List<Anomaly> findByTimestampBetween(Instant from, Instant to);

    List<Anomaly> findByMachineIdAndStatus(String machineId, String status);

    long countByStatus(String status);

    long countByMachineIdAndStatus(String machineId, String status);

    void deleteByMachineId(String machineId);
}
