package com.smartfactory.controller;

import com.smartfactory.entity.Anomaly;
import com.smartfactory.entity.Machine;
import com.smartfactory.entity.SensorType;
import com.smartfactory.service.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * Contrôleur REST pour la détection et l'analyse d'anomalies (Sprint 5 - US33, US34, US35, US36).
 */
@RestController
@RequestMapping("/api/anomalies")
public class AnomalyController {

    private final AnomalyService anomalyService;
    private final AdvancedAnomalyDetectionService advancedDetectionService;
    private final MultiSensorAnalysisService multiSensorAnalysisService;
    private final AnomalyScoreService anomalyScoreService;
    private final SensorReadingService sensorReadingService;

    public AnomalyController(AnomalyService anomalyService,
                             AdvancedAnomalyDetectionService advancedDetectionService,
                             MultiSensorAnalysisService multiSensorAnalysisService,
                             AnomalyScoreService anomalyScoreService,
                             SensorReadingService sensorReadingService) {
        this.anomalyService = anomalyService;
        this.advancedDetectionService = advancedDetectionService;
        this.multiSensorAnalysisService = multiSensorAnalysisService;
        this.anomalyScoreService = anomalyScoreService;
        this.sensorReadingService = sensorReadingService;
    }

    /**
     * Consultation paginée des anomalies (US33).
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ALERT_READ', 'ALERT:CONSULTER', 'MACHINE_READ')")
    public ResponseEntity<Page<Anomaly>> getAnomalies(
            @RequestParam(required = false) String machineId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "timestamp"));
        Page<Anomaly> anomalies = anomalyService.getAnomalies(machineId, status, from, to, pageable);
        return ResponseEntity.ok(anomalies);
    }

    /**
     * Consultation du détail d'une anomalie par son ID (US44).
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ALERT_READ', 'ALERT:CONSULTER', 'MACHINE_READ')")
    public ResponseEntity<Anomaly> getAnomalyById(@PathVariable String id) {
        return ResponseEntity.ok(anomalyService.getAnomalyById(id));
    }

    /**
     * Score d'anomalie normalisé sur 100 pour une machine (US36).
     */
    @GetMapping("/machines/{machineId}/score")
    @PreAuthorize("hasAnyAuthority('ALERT_READ', 'ALERT:CONSULTER', 'MACHINE_READ')")
    public ResponseEntity<AnomalyScoreService.AnomalyScoreResult> getMachineAnomalyScore(@PathVariable String machineId) {
        return ResponseEntity.ok(anomalyScoreService.calculateScore(machineId));
    }

    /**
     * Analyse corrélée multi-capteurs pour une machine (US35).
     */
    @GetMapping("/machines/{machineId}/multi-sensor")
    @PreAuthorize("hasAnyAuthority('ALERT_READ', 'ALERT:CONSULTER', 'MACHINE_READ')")
    public ResponseEntity<MultiSensorAnalysisService.MultiSensorAnalysisResult> getMultiSensorAnalysis(@PathVariable String machineId) {
        Machine machine = sensorReadingService.resolveMachine(machineId);
        return ResponseEntity.ok(multiSensorAnalysisService.analyzeMachine(machine));
    }

    /**
     * Détection avancée par modélisation statistique comportementale (US34).
     */
    @GetMapping("/machines/{machineId}/sensors/{sensorType}/advanced-detection")
    @PreAuthorize("hasAnyAuthority('ALERT_READ', 'ALERT:CONSULTER', 'MACHINE_READ')")
    public ResponseEntity<AdvancedAnomalyDetectionService.DetectionResult> getAdvancedDetection(
            @PathVariable String machineId,
            @PathVariable String sensorType,
            @RequestParam(required = false) Double currentValue) {

        SensorType type = SensorType.fromString(sensorType);
        Machine machine = sensorReadingService.resolveMachine(machineId);
        return ResponseEntity.ok(advancedDetectionService.analyzeSensorBehavior(machine.getId(), type, currentValue));
    }
}
