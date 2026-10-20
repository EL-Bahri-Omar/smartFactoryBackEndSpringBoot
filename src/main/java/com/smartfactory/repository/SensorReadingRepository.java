package com.smartfactory.repository;

import com.smartfactory.entity.SensorReading;
import com.smartfactory.entity.SensorType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface SensorReadingRepository extends MongoRepository<SensorReading, String> {

    Page<SensorReading> findByMachineId(String machineId, Pageable pageable);

    Page<SensorReading> findByMachineIdAndTimestampBetween(String machineId, Instant from, Instant to, Pageable pageable);

    Page<SensorReading> findByMachineIdAndSensorType(String machineId, SensorType sensorType, Pageable pageable);

    Page<SensorReading> findByMachineIdAndSensorTypeAndTimestampBetween(String machineId, SensorType sensorType, Instant from, Instant to, Pageable pageable);

    Page<SensorReading> findByMachineCode(String machineCode, Pageable pageable);

    Page<SensorReading> findByMachineCodeAndTimestampBetween(String machineCode, Instant from, Instant to, Pageable pageable);

    Page<SensorReading> findByMachineCodeAndSensorType(String machineCode, SensorType sensorType, Pageable pageable);

    Page<SensorReading> findByMachineCodeAndSensorTypeAndTimestampBetween(String machineCode, SensorType sensorType, Instant from, Instant to, Pageable pageable);

    List<SensorReading> findByMachineId(String machineId, Sort sort);

    List<SensorReading> findByMachineIdAndTimestampBetween(String machineId, Instant from, Instant to, Sort sort);

    List<SensorReading> findByMachineIdAndSensorType(String machineId, SensorType sensorType, Sort sort);

    List<SensorReading> findByMachineIdAndSensorTypeAndTimestampBetween(String machineId, SensorType sensorType, Instant from, Instant to, Sort sort);

    List<SensorReading> findByMachineCodeAndTimestampBetween(String machineCode, Instant from, Instant to, Sort sort);

    List<SensorReading> findByMachineCodeAndSensorTypeAndTimestampBetween(String machineCode, SensorType sensorType, Instant from, Instant to, Sort sort);

    boolean existsByMachineIdAndSensorIdAndTimestamp(String machineId, String sensorId, Instant timestamp);

    boolean existsBySensorIdAndTimestamp(String sensorId, Instant timestamp);

    boolean existsByMachineIdAndSensorTypeAndTimestamp(String machineId, SensorType sensorType, Instant timestamp);

    Page<SensorReading> findBySensorId(String sensorId, Pageable pageable);

    Page<SensorReading> findBySensorIdAndTimestampBetween(String sensorId, Instant from, Instant to, Pageable pageable);

    Page<SensorReading> findBySensorIdIn(java.util.Collection<String> sensorIds, Pageable pageable);

    Page<SensorReading> findBySensorIdInAndTimestampBetween(java.util.Collection<String> sensorIds, Instant from, Instant to, Pageable pageable);

    Page<SensorReading> findBySensorIdInAndSensorType(java.util.Collection<String> sensorIds, SensorType sensorType, Pageable pageable);

    Page<SensorReading> findBySensorIdInAndSensorTypeAndTimestampBetween(java.util.Collection<String> sensorIds, SensorType sensorType, Instant from, Instant to, Pageable pageable);

    List<SensorReading> findBySensorIdInAndTimestampBetween(java.util.Collection<String> sensorIds, Instant from, Instant to, Sort sort);

    List<SensorReading> findBySensorIdInAndSensorTypeAndTimestampBetween(java.util.Collection<String> sensorIds, SensorType sensorType, Instant from, Instant to, Sort sort);

    void deleteByMachineId(String machineId);
}
