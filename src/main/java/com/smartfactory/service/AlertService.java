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
    private final AnomalyService anomalyService;
    private final MachineRepository machineRepository;
    private final NotificationService notificationService;
    private final RealtimeMessageService realtimeMessageService;

    private final java.util.concurrent.ConcurrentHashMap<String, Object> sensorLocks = new java.util.concurrent.ConcurrentHashMap<>();

    public AlertService(AlertRepository alertRepository,
                        AnomalyRepository anomalyRepository,
                        AnomalyService anomalyService,
                        MachineRepository machineRepository,
                        NotificationService notificationService,
                        RealtimeMessageService realtimeMessageService) {
        this.alertRepository = alertRepository;
        this.anomalyRepository = anomalyRepository;
        this.anomalyService = anomalyService;
        this.machineRepository = machineRepository;
        this.notificationService = notificationService;
        this.realtimeMessageService = realtimeMessageService;
    }

    /**
     * Traite intégralement le cycle de vie et les transitions d'état d'un capteur (Règles A, B, C, D, E).
     *
     * Transitions prises en charge :
     * - NORMAL -> WARNING : Création d'une alerte unique WARNING et notification (Règle A).
     * - WARNING -> WARNING : Maintien sans spam ni duplication (Règle C).
     * - WARNING -> CRITICAL : Transition d'état sur l'alerte active, mise à jour de l'indicateur visuel et notification (Règle B).
     * - CRITICAL -> CRITICAL : Maintien sans duplication (Règle C).
     * - CRITICAL/WARNING -> NORMAL : Clôture/résolution automatique de l'alerte active et de l'anomalie (Règle D).
     * - Isolation stricte par machine et type de capteur (Règle E).
     */
    public Optional<Alert> processSensorReadingTransition(
            Machine machine,
            Sensor sensor,
            SensorType sensorType,
            double value,
            MachineThreshold.EvaluationResult evaluation,
            Instant timestamp) {

        String machineId = machine != null ? machine.getId() : (sensor != null ? sensor.getMachineId() : null);
        if (!StringUtils.hasText(machineId) || sensorType == null) {
            return Optional.empty();
        }

        String lockKey = machineId + ":" + sensorType.name();
        synchronized (sensorLocks.computeIfAbsent(lockKey, k -> new Object())) {
            Instant effectiveTimestamp = timestamp != null ? timestamp : Instant.now();
            String machineCode = machine != null ? machine.getCode() : machineId;

            boolean isAlert = evaluation != null && evaluation.isAlertTriggered();
            Optional<Alert> activeAlertOpt = alertRepository.findFirstByMachineIdAndSensorTypeAndStatusOrderByTimestampDesc(
                    machineId, sensorType, "ACTIVE"
            );

            // RÈGLE D : Retour à NORMAL -> Clôturer automatiquement l'alerte active
            if (!isAlert) {
                if (activeAlertOpt.isPresent()) {
                    Alert activeAlert = activeAlertOpt.get();
                    log.info("Retour à l'état NORMAL pour machine [{}] ({}) -> Clôture automatique de l'alerte active [{}]",
                            machineCode, sensorType, activeAlert.getId());
                    resolveAlert(activeAlert.getId(), "SYSTEM", "Retour à la normale automatique de la mesure (valeur: " + value + ")");

                    // Nettoyer les doublons résiduels éventuels
                    List<Alert> allActives = alertRepository.findByMachineIdAndSensorTypeAndStatus(machineId, sensorType, "ACTIVE");
                    for (Alert extra : allActives) {
                        resolveAlert(extra.getId(), "SYSTEM", "Clôture doublon résiduel");
                    }
                }

                // Clôturer l'anomalie associée si ouverte
                anomalyRepository.findFirstByMachineIdAndSensorTypeAndStatusIn(
                        machineId, sensorType, List.of("OPEN", "ACKNOWLEDGED")
                ).ifPresent(anomaly -> {
                    anomaly.setStatus("RESOLVED");
                    anomaly.setUpdatedAt(Instant.now());
                    anomalyRepository.save(anomaly);
                });

                return Optional.empty();
            }

            // Ici, l'état est WARNING ou CRITICAL (ou HIGH)
            String effectiveSeverity = evaluation.getSeverity() != null ? evaluation.getSeverity().toUpperCase() : "WARNING";
            Double threshold = evaluation.getBreachedThreshold();
            String message = evaluation.getMessage();

            // Enregistrer ou mettre à jour l'anomalie (déduplication)
            Optional<Anomaly> anomalyOpt = anomalyService.processReadingAnomaly(
                    machine, sensor, sensorType, value, evaluation, effectiveTimestamp
            );
            String anomalyId = anomalyOpt.map(Anomaly::getId).orElse(null);

            // RÈGLE A : Première détection (NORMAL -> WARNING ou NORMAL -> CRITICAL)
            if (activeAlertOpt.isEmpty()) {
                Alert newAlert = new Alert(
                        machineId,
                        machineCode,
                        sensor != null ? sensor.getId() : null,
                        sensorType,
                        anomalyId,
                        effectiveSeverity,
                        value,
                        threshold,
                        message,
                        effectiveTimestamp
                );
                Alert savedAlert = alertRepository.save(newAlert);
                log.warn("Nouvelle alerte active créée [{}] pour machine {} ({}) : {}",
                        savedAlert.getSeverity(), machineCode, sensorType, message);

                if (anomalyOpt.isPresent()) {
                    Anomaly anomaly = anomalyOpt.get();
                    anomaly.setAlertId(savedAlert.getId());
                    anomalyRepository.save(anomaly);
                }

                // Diffusion WebSocket temps réel
                realtimeMessageService.broadcastAlert(savedAlert, machineCode);

                // Notification conforme
                try {
                    notificationService.notifyAlert(savedAlert);
                } catch (Exception ex) {
                    log.error("Échec non-bloquant de notification pour l'alerte {} : {}", savedAlert.getId(), ex.getMessage());
                }

                return Optional.of(savedAlert);
            }

            // Alerte active existante
            Alert existing = activeAlertOpt.get();
            String currentSeverity = existing.getSeverity() != null ? existing.getSeverity().toUpperCase() : "WARNING";

            // RÈGLE B : Transition d'état (ex: WARNING -> CRITICAL)
            if (!currentSeverity.equalsIgnoreCase(effectiveSeverity)) {
                log.info("Transition d'alerte pour machine {} ({}) : {} -> {}",
                        machineCode, sensorType, currentSeverity, effectiveSeverity);

                existing.setSeverity(effectiveSeverity);
                existing.setValue(value);
                existing.setThreshold(threshold);
                existing.setMessage(message);
                existing.setTimestamp(effectiveTimestamp);
                if (StringUtils.hasText(anomalyId)) {
                    existing.setAnomalyId(anomalyId);
                }
                Alert updated = alertRepository.save(existing);

                // Nettoyer d'éventuels doublons orphelins actifs
                List<Alert> duplicates = alertRepository.findByMachineIdAndSensorTypeAndStatus(machineId, sensorType, "ACTIVE");
                for (Alert dup : duplicates) {
                    if (!dup.getId().equals(updated.getId())) {
                        resolveAlert(dup.getId(), "SYSTEM", "Clôture doublon de transition");
                    }
                }

                // Mettre à jour l'indicateur visuel via WebSocket (retire point WARNING, met point CRITICAL)
                realtimeMessageService.broadcastAlert(updated, machineCode);

                // Notification pour l'événement de transition
                try {
                    notificationService.notifyAlert(updated);
                } catch (Exception ex) {
                    log.error("Échec notification transition alerte {} : {}", updated.getId(), ex.getMessage());
                }

                return Optional.of(updated);
            }

            // RÈGLE C : Maintien dans le même état (WARNING -> WARNING ou CRITICAL -> CRITICAL)
            // Mise à jour discrète des mesures sans créer de nouvelle alerte ni notification
            existing.setValue(value);
            existing.setThreshold(threshold);
            existing.setMessage(message);
            existing.setTimestamp(effectiveTimestamp);
            if (StringUtils.hasText(anomalyId)) {
                existing.setAnomalyId(anomalyId);
            }
            Alert updated = alertRepository.save(existing);
            log.debug("Alerte active maintenue [{}] pour machine {} ({}) : valeur actualisée {}",
                    existing.getId(), machineCode, sensorType, value);

            return Optional.of(updated);
        }
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

        // 1. Déduplication : Vérifier si une alerte ACTIVE existe déjà pour ce capteur
        Optional<Alert> existingOpt = alertRepository.findFirstByMachineIdAndSensorTypeAndStatusOrderByTimestampDesc(
                machineId, anomaly.getSensorType(), "ACTIVE"
        );

        if (existingOpt.isPresent()) {
            Alert existing = existingOpt.get();
            boolean severityChanged = existing.getSeverity() == null || !existing.getSeverity().equalsIgnoreCase(effectiveSeverity);

            existing.setSeverity(effectiveSeverity);
            existing.setValue(value);
            existing.setThreshold(threshold);
            existing.setMessage(message);
            existing.setAnomalyId(anomaly.getId());
            Alert updated = alertRepository.save(existing);
            log.debug("Alerte active existante mise à jour [{}] pour machine {}", updated.getId(), machineCode);

            if (severityChanged) {
                realtimeMessageService.broadcastAlert(updated, machineCode);
                try {
                    notificationService.notifyAlert(updated);
                } catch (Exception ex) {
                    log.error("Échec non-bloquant de notification pour transition d'alerte {} : {}", updated.getId(), ex.getMessage());
                }
            }

            return updated;
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

        // 4. Déclenchement de la notification FCM / in-app (US40)
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
