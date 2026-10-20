package com.smartfactory.service;

import com.smartfactory.entity.*;
import com.smartfactory.repository.AlertRepository;
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
 * Service de gestion intégrale du cycle de vie des alertes (Sprint 5 - US37, US38, US39, US41).
 *
 * Fonctionnalités :
 * - Création d'alerte avec liaison à l'objet Anomaly (US37).
 * - Hiérarchisation de la criticité en 4 niveaux : INFO, WARNING, HIGH, CRITICAL (US38).
 * - Déduplication des alertes actives pour éviter les tempêtes de notifications.
 * - Consultation avec pagination et filtres multi-critères (US39).
 * - Notification multi-canaux (FCM + in-app) via NotificationService (US40).
 * - Acquittement formel avec traçabilité de l'opérateur/technicien (US41).
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AlertRepository alertRepository;
    private final AnomalyRepository anomalyRepository;
    private final MachineRepository machineRepository;
    private final NotificationService notificationService;
    private final RealtimeMessageService realtimeMessageService;

    public AlertService(AlertRepository alertRepository,
                        AnomalyRepository anomalyRepository,
                        MachineRepository machineRepository,
                        NotificationService notificationService,
                        RealtimeMessageService realtimeMessageService) {
        this.alertRepository = alertRepository;
        this.anomalyRepository = anomalyRepository;
        this.machineRepository = machineRepository;
        this.notificationService = notificationService;
        this.realtimeMessageService = realtimeMessageService;
    }

    /**
     * Détermine la criticité industrielle selon les seuils et l'ampleur du dépassement (US38).
     *
     * Règles documentées :
     * 1. CRITICAL : Dépassement d'un seuil critique (criticalMin/criticalMax), température > 80°C, vibration > 4.5 mm/s.
     * 2. HIGH : Anomalie multi-capteurs corrélée ou dépassement critique persistant.
     * 3. WARNING : Dépassement d'un seuil d'avertissement (warningMin/warningMax), ou capteur hors-ligne.
     * 4. INFO : Rétablissement, dérive statistique légère ou notification de routine.
     */
    public String calculateSeverity(MachineThreshold.EvaluationResult evaluation, double value, Double breachedThreshold) {
        if (evaluation != null && StringUtils.hasText(evaluation.getSeverity())) {
            String sev = evaluation.getSeverity().trim().toUpperCase();
            if (Set.of("CRITICAL", "HIGH", "WARNING", "INFO").contains(sev)) {
                return sev;
            }
        }
        return "WARNING";
    }

    /**
     * Crée ou met à jour une alerte liée à une anomalie détectée (US37).
     * Empêche la multiplication d'alertes identiques pour un même incident actif.
     */
    public Alert createOrUpdateAlertForAnomaly(Anomaly anomaly, Machine machine, String severity, Double value, Double threshold, String message) {
        if (anomaly == null) {
            return null;
        }

        String effectiveSeverity = StringUtils.hasText(severity) ? severity.toUpperCase() : "WARNING";
        String machineId = anomaly.getMachineId();
        String machineCode = machine != null ? machine.getCode() : anomaly.getMachineCode();

        // 1. Déduplication : Vérifier si une alerte ACTIVE avec la même sévérité existe déjà pour ce capteur
        Optional<Alert> existingOpt = alertRepository.findFirstByMachineIdAndSensorTypeAndStatus(
                machineId, anomaly.getSensorType(), "ACTIVE"
        );

        if (existingOpt.isPresent()) {
            Alert existing = existingOpt.get();
            if (existing.getSeverity() != null && existing.getSeverity().equalsIgnoreCase(effectiveSeverity)) {
                existing.setValue(value);
                existing.setThreshold(threshold);
                existing.setMessage(message);
                existing.setAnomalyId(anomaly.getId());
                Alert updated = alertRepository.save(existing);
                log.debug("Alerte active existante mise à jour [{}] pour machine {}", updated.getId(), machineCode);
                return updated;
            }
        }

        // 2. Création d'une nouvelle alerte ACTIVE
        Alert newAlert = new Alert(
                machineId,
                machineCode,
                anomaly.getSensorId(),
                anomaly.getSensorType(),
                anomaly.getId(),
                effectiveSeverity,
                value,
                threshold,
                message,
                anomaly.getTimestamp()
        );

        Alert savedAlert = alertRepository.save(newAlert);
        log.warn("Nouvelle alerte créée [{}] pour machine {} ({}) : {}",
                savedAlert.getSeverity(), machineCode, anomaly.getSensorType(), message);

        // Liaison bidirectionnelle avec l'objet Anomaly
        anomaly.setAlertId(savedAlert.getId());
        anomalyRepository.save(anomaly);

        // 3. Diffusion temps réel WebSocket (STOMP)
        realtimeMessageService.broadcastAlert(savedAlert, machineCode);

        // 4. Déclenchement de la notification FCM / in-app si HIGH ou CRITICAL (US40)
        try {
            notificationService.notifyAlert(savedAlert);
        } catch (Exception ex) {
            log.error("Échec non-bloquant de notification pour l'alerte {} : {}", savedAlert.getId(), ex.getMessage());
        }

        return savedAlert;
    }

    /**
     * Consultation des alertes avec filtres et pagination (US39).
     */
    public Page<Alert> getAlerts(String machineId, String severity, String status, Instant from, Instant to, Pageable pageable) {
        if (StringUtils.hasText(machineId) && StringUtils.hasText(severity) && StringUtils.hasText(status)) {
            return alertRepository.findByMachineIdAndSeverityAndStatus(machineId.trim(), severity.trim().toUpperCase(), status.trim().toUpperCase(), pageable);
        } else if (StringUtils.hasText(machineId) && StringUtils.hasText(status)) {
            return alertRepository.findByMachineIdAndStatus(machineId.trim(), status.trim().toUpperCase(), pageable);
        } else if (StringUtils.hasText(machineId) && StringUtils.hasText(severity)) {
            return alertRepository.findByMachineIdAndSeverity(machineId.trim(), severity.trim().toUpperCase(), pageable);
        } else if (StringUtils.hasText(machineId)) {
            return alertRepository.findByMachineId(machineId.trim(), pageable);
        } else if (StringUtils.hasText(status)) {
            return alertRepository.findByStatus(status.trim().toUpperCase(), pageable);
        } else if (StringUtils.hasText(severity)) {
            return alertRepository.findBySeverity(severity.trim().toUpperCase(), pageable);
        }
        return alertRepository.findAll(pageable);
    }

    public Alert getAlertById(String id) {
        return alertRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alerte introuvable : " + id));
    }

    /**
     * Acquittement formel d'une alerte par un technicien/opérateur (US41).
     */
    public Alert acknowledgeAlert(String alertId, String userId, String comment) {
        Alert alert = getAlertById(alertId);

        if ("RESOLVED".equalsIgnoreCase(alert.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Impossible d'acquitter une alerte déjà résolue");
        }
        if ("ACKNOWLEDGED".equalsIgnoreCase(alert.getStatus())) {
            log.debug("Alerte {} déjà acquittée par {}", alertId, alert.getAcknowledgedBy());
            return alert;
        }

        alert.setStatus("ACKNOWLEDGED");
        alert.setAcknowledgedBy(StringUtils.hasText(userId) ? userId : "CURRENT_USER");
        alert.setAcknowledgedAt(Instant.now());
        if (StringUtils.hasText(comment)) {
            alert.setComment(comment.trim());
        }

        Alert saved = alertRepository.save(alert);
        log.info("Alerte [{}] acquittée par [{}] à {}", saved.getId(), saved.getAcknowledgedBy(), saved.getAcknowledgedAt());

        // Mettre à jour l'anomalie associée si elle existe
        if (StringUtils.hasText(saved.getAnomalyId())) {
            anomalyRepository.findById(saved.getAnomalyId()).ifPresent(anomaly -> {
                anomaly.setStatus("ACKNOWLEDGED");
                anomalyRepository.save(anomaly);
            });
        }

        // Diffusion du changement d'état via WebSocket
        realtimeMessageService.broadcastAlert(saved, saved.getMachineCode());

        return saved;
    }

    /**
     * Résolution définitive d'une alerte (généralement clôturée après intervention de maintenance).
     */
    public Alert resolveAlert(String alertId, String userId, String comment) {
        Alert alert = getAlertById(alertId);

        alert.setStatus("RESOLVED");
        alert.setResolvedBy(StringUtils.hasText(userId) ? userId : "CURRENT_USER");
        alert.setResolvedAt(Instant.now());
        if (StringUtils.hasText(comment)) {
            alert.setComment(comment.trim());
        }

        Alert saved = alertRepository.save(alert);
        log.info("Alerte [{}] résolue par [{}] à {}", saved.getId(), saved.getResolvedBy(), saved.getResolvedAt());

        if (StringUtils.hasText(saved.getAnomalyId())) {
            anomalyRepository.findById(saved.getAnomalyId()).ifPresent(anomaly -> {
                anomaly.setStatus("RESOLVED");
                anomalyRepository.save(anomaly);
            });
        }

        realtimeMessageService.broadcastAlert(saved, saved.getMachineCode());
        return saved;
    }

    /**
     * Récupère le résumé chiffré des alertes pour le tableau de bord de supervision.
     */
    public Map<String, Object> getAlertSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("activeCount", alertRepository.countByStatus("ACTIVE"));
        summary.put("acknowledgedCount", alertRepository.countByStatus("ACKNOWLEDGED"));
        summary.put("resolvedCount", alertRepository.countByStatus("RESOLVED"));
        summary.put("criticalActiveCount", alertRepository.countBySeverityAndStatus("CRITICAL", "ACTIVE"));
        summary.put("highActiveCount", alertRepository.countBySeverityAndStatus("HIGH", "ACTIVE"));
        summary.put("warningActiveCount", alertRepository.countBySeverityAndStatus("WARNING", "ACTIVE"));
        summary.put("infoActiveCount", alertRepository.countBySeverityAndStatus("INFO", "ACTIVE"));
        return summary;
    }
}
