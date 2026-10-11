package com.smartfactory.controller;

import com.smartfactory.entity.MachineThreshold;
import com.smartfactory.entity.SensorType;
import com.smartfactory.service.MachineThresholdService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Contrôleur REST pour la configuration des seuils de surveillance des machines.
 * Stockés dans la base de données MongoDB, modifiables à chaud sans redémarrage.
 */
@RestController
@RequestMapping("/api/machines/{machineId}/thresholds")
public class MachineThresholdController {

    private final MachineThresholdService thresholdService;

    public MachineThresholdController(MachineThresholdService thresholdService) {
        this.thresholdService = thresholdService;
    }

    /**
     * Consulte l'ensemble des seuils de surveillance pour une machine donnée.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('THRESHOLD_READ')")
    public ResponseEntity<List<MachineThreshold>> getThresholds(@PathVariable String machineId) {
        List<MachineThreshold> thresholds = thresholdService.getThresholdsByMachine(machineId);
        return ResponseEntity.ok(thresholds);
    }

    /**
     * Consulte le seuil d'une machine pour un type de mesure/capteur spécifique.
     */
    @GetMapping("/{sensorType}")
    @PreAuthorize("hasAuthority('THRESHOLD_READ')")
    public ResponseEntity<MachineThreshold> getThreshold(
            @PathVariable String machineId,
            @PathVariable String sensorType) {
        SensorType type = parseSensorType(sensorType);
        MachineThreshold threshold = thresholdService.getThreshold(machineId, type);
        return ResponseEntity.ok(threshold);
    }

    /**
     * Crée ou remplace un seuil de surveillance pour une machine.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('THRESHOLD_WRITE')")
    public ResponseEntity<MachineThreshold> createOrUpdateThreshold(
            @PathVariable String machineId,
            @Valid @RequestBody MachineThreshold threshold) {
        MachineThreshold saved = thresholdService.saveOrUpdateThreshold(machineId, threshold);
        return new ResponseEntity<>(saved, HttpStatus.CREATED);
    }

    /**
     * Met à jour le seuil d'une machine pour un type de capteur spécifique.
     */
    @PutMapping("/{sensorType}")
    @PreAuthorize("hasAuthority('THRESHOLD_WRITE')")
    public ResponseEntity<MachineThreshold> updateThreshold(
            @PathVariable String machineId,
            @PathVariable String sensorType,
            @RequestBody MachineThreshold threshold) {
        SensorType type = parseSensorType(sensorType);
        threshold.setSensorType(type);
        MachineThreshold updated = thresholdService.saveOrUpdateThreshold(machineId, threshold);
        return ResponseEntity.ok(updated);
    }

    /**
     * Supprime un seuil de surveillance spécifique pour une machine.
     */
    @DeleteMapping("/{sensorType}")
    @PreAuthorize("hasAuthority('THRESHOLD_WRITE')")
    public ResponseEntity<Void> deleteThreshold(
            @PathVariable String machineId,
            @PathVariable String sensorType) {
        SensorType type = parseSensorType(sensorType);
        thresholdService.deleteThreshold(machineId, type);
        return ResponseEntity.noContent().build();
    }

    private SensorType parseSensorType(String sensorTypeStr) {
        SensorType type = SensorType.fromString(sensorTypeStr);
        if (type == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR: Invalid sensor type: " + sensorTypeStr
            );
        }
        return type;
    }
}
