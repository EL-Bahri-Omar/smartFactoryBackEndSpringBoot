package com.smartfactory.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Modèle métier d'une anomalie détectée sur une machine industrielle (Sprint 5 - US33).
 * Persisté dans la collection 'anomalies' de la base opérationnelle (séparé des mesures compactes).
 */
@Document(collection = "anomalies")
@CompoundIndexes({
        @CompoundIndex(name = "machine_sensor_status_idx", def = "{'machineId': 1, 'sensorType': 1, 'status': 1}"),
        @CompoundIndex(name = "machine_ts_idx", def = "{'machineId': 1, 'timestamp': -1}"),
        @CompoundIndex(name = "status_ts_idx", def = "{'status': 1, 'timestamp': -1}")
})
public class Anomaly {

    @Id
    private String id;

    @Indexed
    private String machineId;

    private String machineCode;

    @Indexed
    private String sensorId;

    private SensorType sensorType;

    private Double observedValue;

    private Double thresholdValue;

    private String thresholdType; // WARNING_MIN, WARNING_MAX, CRITICAL_MIN, CRITICAL_MAX, STATISTICAL, MULTI_SENSOR

    private String severity; // INFO, WARNING, HIGH, CRITICAL

    private Instant timestamp;

    private String status = "OPEN"; // OPEN, ACKNOWLEDGED, RESOLVED

    private Instant lastOccurrence;

    private int occurrenceCount = 1;

    private String message;

    private String alertId;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public Anomaly() {
    }

    public Anomaly(String machineId, String machineCode, String sensorId, SensorType sensorType,
                   Double observedValue, Double thresholdValue, String thresholdType,
                   String severity, String message, Instant timestamp) {
        this.machineId = machineId;
        this.machineCode = machineCode;
        this.sensorId = sensorId;
        this.sensorType = sensorType;
        this.observedValue = observedValue;
        this.thresholdValue = thresholdValue;
        this.thresholdType = thresholdType;
        this.severity = severity != null ? severity : "WARNING";
        this.message = message;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.lastOccurrence = this.timestamp;
        this.occurrenceCount = 1;
        this.status = "OPEN";
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMachineId() {
        return machineId;
    }

    public void setMachineId(String machineId) {
        this.machineId = machineId;
    }

    public String getMachineCode() {
        return machineCode;
    }

    public void setMachineCode(String machineCode) {
        this.machineCode = machineCode;
    }

    public String getSensorId() {
        return sensorId;
    }

    public void setSensorId(String sensorId) {
        this.sensorId = sensorId;
    }

    public SensorType getSensorType() {
        return sensorType;
    }

    public void setSensorType(SensorType sensorType) {
        this.sensorType = sensorType;
    }

    public Double getObservedValue() {
        return observedValue;
    }

    public void setObservedValue(Double observedValue) {
        this.observedValue = observedValue;
    }

    public Double getThresholdValue() {
        return thresholdValue;
    }

    public void setThresholdValue(Double thresholdValue) {
        this.thresholdValue = thresholdValue;
    }

    public String getThresholdType() {
        return thresholdType;
    }

    public void setThresholdType(String thresholdType) {
        this.thresholdType = thresholdType;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getLastOccurrence() {
        return lastOccurrence;
    }

    public void setLastOccurrence(Instant lastOccurrence) {
        this.lastOccurrence = lastOccurrence;
    }

    public int getOccurrenceCount() {
        return occurrenceCount;
    }

    public void setOccurrenceCount(int occurrenceCount) {
        this.occurrenceCount = occurrenceCount;
    }

    public void incrementOccurrence() {
        this.occurrenceCount++;
        this.lastOccurrence = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getAlertId() {
        return alertId;
    }

    public void setAlertId(String alertId) {
        this.alertId = alertId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
