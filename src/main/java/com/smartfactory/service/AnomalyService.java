package com.smartfactory.service;

import com.smartfactory.entity.*;
import com.smartfactory.repository.AnomalyRepository;
import com.smartfactory.repository.MachineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

/**
 * Service de détection et gestion des anomalies par seuil (Sprint 5 - US33).
 *
 * Fonctionnalités :
 * - Analyse chaque mesure par rapport aux seuils configurés.
 * - Détecte les dépassements minimaux et maximaux.
 * - Stratégie anti-flood / déduplication : Si un incident est déjà 'OPEN',
 *   incrémente occurrenceCount et actualise lastOccurrence au lieu de créer
 *   des milliers de documents identiques à chaque mesure de 3s.
 * - Persistance séparée dans la collection 'anomalies' de la base opérationnelle.
 */
@Service
public class AnomalyService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyService.class);

    private final AnomalyRepository anomalyRepository;
    private final MachineRepository machineRepository;

    public AnomalyService(AnomalyRepository anomalyRepository,
                          MachineRepository machineRepository) {
        this.anomalyRepository = anomalyRepository;
        this.machineRepository = machineRepository;
    }

    /**
     * Traite l'évaluation d'une mesure et enregistre l'anomalie correspondante avec déduplication.
     */
    public Optional<Anomaly> processReadingAnomaly(Machine machine,
                                                  Sensor sensor,
                                                  SensorType sensorType,
                                                  double value,
                                                  MachineThreshold.EvaluationResult evaluation,
                                                  Instant timestamp) {
        if (evaluation == null || !evaluation.isAlertTriggered()) {
            return Optional.empty();
        }

        String machineId = machine != null ? machine.getId() : (sensor != null ? sensor.getMachineId() : null);
        String machineCode = machine != null ? machine.getCode() : machineId;
        String sensorId = sensor != null ? sensor.getId() : null;
        Instant effectiveTimestamp = timestamp != null ? timestamp : Instant.now();

        String severity = evaluation.getSeverity() != null ? evaluation.getSeverity() : "WARNING";
        Double thresholdVal = evaluation.getBreachedThreshold();
        String message = evaluation.getMessage() != null ? evaluation.getMessage() :
                String.format("Dépassement de seuil pour %s sur %s : %.2f", sensorType, machineCode, value);

        // Détection du type de seuil (MIN vs MAX)
        String thresholdType = "MAX";
        if (thresholdVal != null && value < thresholdVal) {
            thresholdType = "MIN";
        }
        String qualifiedThresholdType = severity + "_" + thresholdType;

        // Déduplication : rechercher un incident actif ('OPEN' ou 'ACKNOWLEDGED')
        Optional<Anomaly> activeAnomalyOpt = anomalyRepository.findFirstByMachineIdAndSensorTypeAndStatusIn(
                machineId, sensorType, List.of("OPEN", "ACKNOWLEDGED")
        );

        if (activeAnomalyOpt.isPresent()) {
            Anomaly existing = activeAnomalyOpt.get();
            existing.setLastOccurrence(effectiveTimestamp);
            existing.setObservedValue(value);
            existing.setOccurrenceCount(existing.getOccurrenceCount() + 1);
            existing.setUpdatedAt(Instant.now());
            // Si la nouvelle gravité est plus élevée, on élève la sévérité
            if ("CRITICAL".equalsIgnoreCase(severity) && !"CRITICAL".equalsIgnoreCase(existing.getSeverity())) {
                existing.setSeverity("CRITICAL");
                existing.setMessage(message);
            }
            Anomaly updated = anomalyRepository.save(existing);
            log.debug("Incident anomalie existant mis à jour [{}] (occurrences: {}, valeur: {})",
                    updated.getId(), updated.getOccurrenceCount(), value);
            return Optional.of(updated);
        }

        // Créer un nouvel objet Anomaly
        Anomaly newAnomaly = new Anomaly(
                machineId,
                machineCode,
                sensorId,
                sensorType,
                value,
                thresholdVal,
                qualifiedThresholdType,
                severity,
                message,
                effectiveTimestamp
        );

        Anomaly saved = anomalyRepository.save(newAnomaly);
        log.warn("Nouvelle anomalie détectée [{}] sur {} ({}) : valeur={}, seuil={}",
                saved.getSeverity(), machineCode, sensorType, value, thresholdVal);
        return Optional.of(saved);
    }

    /**
     * Recherche une anomalie par son ID (US44).
     */
    public Anomaly getAnomalyById(String id) {
        return anomalyRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Anomalie introuvable avec l'ID : " + id));
    }

    /**
     * Consultation des anomalies avec pagination et filtres multi-critères.
     */
    public Page<Anomaly> getAnomalies(String machineId, String status, Instant from, Instant to, Pageable pageable) {
        if (StringUtils.hasText(machineId) && StringUtils.hasText(status)) {
            return anomalyRepository.findByMachineIdAndStatus(machineId.trim(), status.trim().toUpperCase(), pageable);
        } else if (StringUtils.hasText(machineId) && from != null && to != null) {
            return anomalyRepository.findByMachineIdAndTimestampBetween(machineId.trim(), from, to, pageable);
        } else if (StringUtils.hasText(machineId)) {
            return anomalyRepository.findByMachineId(machineId.trim(), pageable);
        } else if (StringUtils.hasText(status)) {
            return anomalyRepository.findByStatus(status.trim().toUpperCase(), pageable);
        }
        return anomalyRepository.findAll(pageable);
    }

    /**
     * Récupère les anomalies actives d'une machine.
     */
    public List<Anomaly> getActiveAnomaliesForMachine(String machineId) {
        if (!StringUtils.hasText(machineId)) {
            return Collections.emptyList();
        }
        return anomalyRepository.findByMachineId(machineId).stream()
                .filter(a -> !"RESOLVED".equalsIgnoreCase(a.getStatus()))
                .toList();
    }

    /**
     * Marque une anomalie comme acquittée ou résolue.
     */
    public Anomaly updateAnomalyStatus(String id, String status) {
        Anomaly anomaly = getAnomalyById(id);
        if (!StringUtils.hasText(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Statut obligatoire");
        }
        String upper = status.trim().toUpperCase();
        if (!Set.of("OPEN", "ACKNOWLEDGED", "RESOLVED").contains(upper)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Statut invalide : " + status);
        }
        anomaly.setStatus(upper);
        anomaly.setUpdatedAt(Instant.now());
        return anomalyRepository.save(anomaly);
    }

    public long countActiveAnomalies(String machineId) {
        return anomalyRepository.countByMachineIdAndStatus(machineId, "OPEN")
                + anomalyRepository.countByMachineIdAndStatus(machineId, "ACKNOWLEDGED");
    }
}
