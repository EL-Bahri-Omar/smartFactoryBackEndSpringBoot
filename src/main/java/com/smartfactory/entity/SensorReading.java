package com.smartfactory.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Modèle de document MongoDB pour la conservation de l'historique des mesures industrielles (Partie 2.1 & US26).
 * Persisté dans les bases hebdomadaires SmartFactory_History_YYYY_Www au format compact :
 * {
 *   _id: ObjectId(...),
 *   sensorId: "...",
 *   sensorType: "VIBRATION",
 *   value: 2.15,
 *   timestamp: ISODate(...)
 * }
 */
@Document(collection = "sensor_readings")
@JsonInclude(JsonInclude.Include.NON_NULL)
@CompoundIndexes({
        @CompoundIndex(name = "sensorId_ts_idx", def = "{'sensorId': 1, 'timestamp': -1}"),
        @CompoundIndex(name = "sensorId_type_ts_idx", def = "{'sensorId': 1, 'sensorType': 1, 'timestamp': -1}"),
        @CompoundIndex(name = "ts_idx", def = "{'timestamp': -1}"),
        @CompoundIndex(name = "machine_ts_idx", def = "{'machineId': 1, 'timestamp': -1}"),
        @CompoundIndex(name = "machineCode_ts_idx", def = "{'machineCode': 1, 'timestamp': -1}"),
        @CompoundIndex(name = "machine_type_ts_idx", def = "{'machineId': 1, 'sensorType': 1, 'timestamp': -1}"),
        @CompoundIndex(name = "machineCode_type_ts_idx", def = "{'machineCode': 1, 'sensorType': 1, 'timestamp': -1}"),
        @CompoundIndex(name = "machine_sensor_ts_idx", def = "{'machineId': 1, 'sensorId': 1, 'timestamp': -1}"),
        @CompoundIndex(name = "dedup_idx", def = "{'machineId': 1, 'sensorId': 1, 'timestamp': 1}")
})
public class SensorReading {

    @Id
    private String id = new org.bson.types.ObjectId().toHexString();

    @Indexed
    private String machineId;

    @Indexed
    private String machineCode;

    @Indexed
    @NotBlank(message = "Sensor ID is required")
    private String sensorId;

    @NotNull(message = "Sensor type is required")
    private SensorType sensorType;

    @NotNull(message = "Measurement value is required")
    private Double value;

    private String unit;

    @Indexed
    @NotNull(message = "Timestamp is required")
    private Instant timestamp;

    private Instant serverReceivedAt;

    private Map<String, Object> metadata;

    @Transient
    private int retryCount = 0;

    public SensorReading() {
    }

    public SensorReading(String machineId, String sensorId, SensorType sensorType, Double value, String unit, Instant timestamp) {
        this.machineId = machineId;
        this.sensorId = sensorId;
        this.sensorType = sensorType;
        this.value = value;
        this.unit = unit;
        this.timestamp = timestamp;
        this.serverReceivedAt = Instant.now();
    }

    public SensorReading(String machineId, String sensorId, SensorType sensorType, Double value, String unit, Instant timestamp, Instant serverReceivedAt) {
        this.machineId = machineId;
        this.sensorId = sensorId;
        this.sensorType = sensorType;
        this.value = value;
        this.unit = unit;
        this.timestamp = timestamp;
        this.serverReceivedAt = serverReceivedAt != null ? serverReceivedAt : Instant.now();
    }

    public SensorReading(String machineId, String machineCode, String sensorId, SensorType sensorType, Double value, String unit, Instant timestamp, Instant serverReceivedAt) {
        this.machineId = machineId;
        this.machineCode = machineCode;
        this.sensorId = sensorId;
        this.sensorType = sensorType;
        this.value = value;
        this.unit = unit;
        this.timestamp = timestamp;
        this.serverReceivedAt = serverReceivedAt != null ? serverReceivedAt : Instant.now();
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

    public Double getValue() {
        return value;
    }

    public void setValue(Double value) {
        this.value = value;
    }

    public String getUnit() {
        if (unit != null) {
            return unit;
        }
        return sensorType != null ? sensorType.getDefaultUnit() : null;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public Instant getServerReceivedAt() {
        return serverReceivedAt;
    }

    public void setServerReceivedAt(Instant serverReceivedAt) {
        this.serverReceivedAt = serverReceivedAt;
    }

    public Map<String, Object> getMetadata() {
        if (metadata == null) {
            metadata = new HashMap<>();
        }
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public static SensorReading createCompact(String sensorId, SensorType sensorType, Double value, Instant timestamp) {
        SensorReading reading = new SensorReading();
        reading.setSensorId(sensorId);
        reading.setSensorType(sensorType);
        reading.setValue(value);
        reading.setTimestamp(timestamp != null ? timestamp : Instant.now());
        return reading;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public int incrementRetryCount() {
        return ++this.retryCount;
    }
}
