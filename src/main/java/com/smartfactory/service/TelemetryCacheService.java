package com.smartfactory.service;

import com.smartfactory.entity.Machine;
import com.smartfactory.entity.MachineStatus;
import com.smartfactory.entity.SensorReading;
import com.smartfactory.entity.SensorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache temps réel en mémoire pour les mesures et les états des machines (Partie D - US20 à US25).
 * Permet de fournir des réponses O(1) instantanées sans solliciter MongoDB lors des consultations
 * de température (US20), vibrations (US21), courant (US22), RPM (US23) et lors des reconnexions.
 */
@Service
public class TelemetryCacheService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryCacheService.class);

    // Clé: machineIdOrCode + ":" + sensorType.name() -> SensorReading
    private final ConcurrentHashMap<String, SensorReading> latestReadings = new ConcurrentHashMap<>();

    // Clé: machineIdOrCode -> Instant de la dernière mesure reçue
    private final ConcurrentHashMap<String, Instant> lastSeenMap = new ConcurrentHashMap<>();

    // Clé: machineIdOrCode -> MachineStatus
    private final ConcurrentHashMap<String, MachineStatus> machineStatusMap = new ConcurrentHashMap<>();

    /**
     * Met à jour le cache avec la dernière lecture reçue.
     */
    public void updateLatestReading(SensorReading reading, String machineCode) {
        if (reading == null) {
            return;
        }

        Instant ts = reading.getTimestamp() != null ? reading.getTimestamp() : Instant.now();
        SensorType type = reading.getSensorType();

        if (StringUtils.hasText(reading.getMachineId()) && type != null) {
            latestReadings.put(reading.getMachineId() + ":" + type.name(), reading);
            lastSeenMap.put(reading.getMachineId(), ts);
        }

        if (StringUtils.hasText(machineCode) && type != null) {
            latestReadings.put(machineCode + ":" + type.name(), reading);
            lastSeenMap.put(machineCode, ts);
        }
    }

    /**
     * Récupère la dernière mesure d'un capteur pour une machine donnée (O(1)).
     */
    public Optional<SensorReading> getLatestReading(String machineIdOrCode, SensorType sensorType) {
        if (!StringUtils.hasText(machineIdOrCode) || sensorType == null) {
            return Optional.empty();
        }
        String key = machineIdOrCode.trim() + ":" + sensorType.name();
        return Optional.ofNullable(latestReadings.get(key));
    }

    /**
     * Récupère un instantané de toutes les dernières mesures pour une machine.
     * Très utile pour l'API de resynchronisation après reconnexion WebSocket (US25).
     */
    public Map<SensorType, SensorReading> getLatestReadingsForMachine(String machineId, String machineCode) {
        Map<SensorType, SensorReading> result = new EnumMap<>(SensorType.class);
        for (SensorType type : SensorType.values()) {
            Optional<SensorReading> opt = getLatestReading(machineId, type);
            if (opt.isEmpty() && StringUtils.hasText(machineCode)) {
                opt = getLatestReading(machineCode, type);
            }
            opt.ifPresent(r -> result.put(type, r));
        }
        return result;
    }

    /**
     * Enregistre ou actualise l'état d'une machine.
     */
    public void setMachineStatus(String machineIdOrCode, MachineStatus status) {
        if (StringUtils.hasText(machineIdOrCode) && status != null) {
            machineStatusMap.put(machineIdOrCode.trim(), status);
        }
    }

    /**
     * Récupère l'état courant en cache pour une machine.
     */
    public Optional<MachineStatus> getMachineStatus(String machineIdOrCode) {
        if (!StringUtils.hasText(machineIdOrCode)) {
            return Optional.empty();
        }
        return Optional.ofNullable(machineStatusMap.get(machineIdOrCode.trim()));
    }

    /**
     * Récupère l'horodatage de la dernière mesure reçue.
     */
    public Optional<Instant> getLastSeen(String machineIdOrCode) {
        if (!StringUtils.hasText(machineIdOrCode)) {
            return Optional.empty();
        }
        return Optional.ofNullable(lastSeenMap.get(machineIdOrCode.trim()));
    }
}
