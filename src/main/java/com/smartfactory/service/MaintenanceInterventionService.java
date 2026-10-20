package com.smartfactory.service;

import com.smartfactory.dto.*;
import com.smartfactory.entity.*;
import com.smartfactory.repository.*;
import com.smartfactory.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Service de gestion complète des interventions de maintenance (Sprint 6 - US42 à US49).
 *
 * Types de maintenance pris en charge :
 * 1. CORRECTIVE : Intervention directe pendant/après panne pour remise en état.
 * 2. PREVENTIVE : Prévention de panne avec sous-types stricts SYSTEMATIC (calendaire/horaire) ou CONDITIONAL (état capteurs).
 * 3. IMPROVEMENT : Amélioration technique, fiabilité, sécurité.
 */
@Service
public class MaintenanceInterventionService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceInterventionService.class);

    private final MaintenanceInterventionRepository interventionRepository;
    private final MachineRepository machineRepository;
    private final UserRepository userRepository;
    private final AlertRepository alertRepository;
    private final AnomalyRepository anomalyRepository;
    private final AlertService alertService;
    private final NotificationService notificationService;
    private final WeeklyHistoryDatabaseService weeklyHistoryDatabaseService;
    private final SensorService sensorService;
    private final MongoTemplate mongoTemplate;

    public MaintenanceInterventionService(MaintenanceInterventionRepository interventionRepository,
                                          MachineRepository machineRepository,
                                          UserRepository userRepository,
                                          AlertRepository alertRepository,
                                          AnomalyRepository anomalyRepository,
                                          AlertService alertService,
                                          NotificationService notificationService,
                                          WeeklyHistoryDatabaseService weeklyHistoryDatabaseService,
                                          SensorService sensorService,
                                          MongoTemplate mongoTemplate) {
        this.interventionRepository = interventionRepository;
        this.machineRepository = machineRepository;
        this.userRepository = userRepository;
        this.alertRepository = alertRepository;
        this.anomalyRepository = anomalyRepository;
        this.alertService = alertService;
        this.notificationService = notificationService;
        this.weeklyHistoryDatabaseService = weeklyHistoryDatabaseService;
        this.sensorService = sensorService;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Crée une nouvelle intervention de maintenance (US42).
     * Applique une validation stricte sur les types et sous-types obligatoires.
     */
    public MaintenanceIntervention createIntervention(InterventionCreateRequest request, String currentUsername) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Requête obligatoire");
        }

        // 1. Validation de la machine
        Machine machine = machineRepository.findByCode(request.getMachineId())
                .or(() -> machineRepository.findById(request.getMachineId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Machine introuvable : " + request.getMachineId()));

        // 2. Validation stricte du type et sous-type de maintenance
        if (request.getType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le type de maintenance est obligatoire");
        }

        if (request.getType() == MaintenanceType.PREVENTIVE) {
            if (request.getPreventiveSubtype() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Le sous-type préventif (SYSTEMATIC ou CONDITIONAL) est OBLIGATOIRE pour une maintenance PREVENTIVE");
            }
        } else {
            if (request.getPreventiveSubtype() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Le sous-type préventif n'est autorisé que pour une maintenance PREVENTIVE (reçu: " + request.getPreventiveSubtype() + ")");
            }
        }

        // 3. Construction de l'intervention
        MaintenanceIntervention intervention = new MaintenanceIntervention(
                machine.getId(),
                machine.getCode(),
                request.getTitle(),
                request.getDescription(),
                request.getType(),
                request.getPreventiveSubtype(),
                request.getPriority()
        );

        intervention.setScheduledAt(request.getScheduledAt());

        // 4. Liaison éventuelle avec une alerte ou anomalie existante
        if (StringUtils.hasText(request.getAlertId())) {
            Alert alert = alertRepository.findById(request.getAlertId().trim())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alerte introuvable : " + request.getAlertId()));
            intervention.setAlertId(alert.getId());
            if (StringUtils.hasText(alert.getAnomalyId())) {
                intervention.setAnomalyId(alert.getAnomalyId());
            }
            // Déduire la priorité si non renseignée
            if (request.getPriority() == null && "CRITICAL".equalsIgnoreCase(alert.getSeverity())) {
                intervention.setPriority(InterventionPriority.CRITICAL);
            } else if (request.getPriority() == null && "HIGH".equalsIgnoreCase(alert.getSeverity())) {
                intervention.setPriority(InterventionPriority.HIGH);
            }
        }

        if (StringUtils.hasText(request.getAnomalyId())) {
            intervention.setAnomalyId(request.getAnomalyId().trim());
        }

        // 5. Affectation initiale d'un technicien si spécifié
        if (StringUtils.hasText(request.getTechnicianId())) {
            assignTechnicianInternal(intervention, request.getTechnicianId().trim(), currentUsername);
        }

        MaintenanceIntervention saved = interventionRepository.save(intervention);
        log.info("Intervention de maintenance créée [{}] ({}) sur machine [{}] par [{}]",
                saved.getId(), saved.getType(), machine.getCode(), currentUsername);
        return saved;
    }

    /**
     * Assigne un technicien éligible à une intervention (US43).
     */
    public MaintenanceIntervention assignTechnician(String interventionId, String technicianId, String assignedBy) {
        MaintenanceIntervention intervention = getInterventionById(interventionId);
        assignTechnicianInternal(intervention, technicianId, assignedBy);
        MaintenanceIntervention saved = interventionRepository.save(intervention);

        // Déclencher notification vers le technicien (FCM + In-app)
        try {
            notificationService.notifyMaintenanceAssigned(technicianId, saved.getId(), saved.getTitle());
        } catch (Exception ex) {
            log.warn("Notification d'assignation échouée pour technicien [{}] : {}", technicianId, ex.getMessage());
        }

        log.info("Intervention [{}] assignée au technicien [{}] par [{}]", saved.getId(), technicianId, assignedBy);
        return saved;
    }

    private void assignTechnicianInternal(MaintenanceIntervention intervention, String technicianId, String assignedBy) {
        User technician = userRepository.findById(technicianId)
                .or(() -> userRepository.findByEmail(technicianId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Technicien introuvable : " + technicianId));

        // Vérification d'éligibilité (Rôle ou privilège)
        boolean isEligible = technician.getRole() == Role.TECHNICIAN
                || technician.getRole() == Role.RESPONSABLE_INDUSTRIEL
                || technician.getRole() == Role.ADMIN
                || (technician.getEffectivePermissions() != null
                && technician.getEffectivePermissions().contains(com.smartfactory.security.Permission.MAINTENANCE_WRITE));

        if (!isEligible) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "L'utilisateur [" + technician.getEmail() + "] n'est pas habilité à effectuer des interventions de maintenance");
        }

        intervention.setTechnicianId(technician.getId());
        intervention.setTechnicianName(technician.getFirstName() != null ? (technician.getFirstName() + " " + technician.getLastName()) : technician.getEmail());
        intervention.setAssignedBy(assignedBy != null ? assignedBy : "SYSTEM");
        intervention.setAssignedAt(Instant.now());

        if (intervention.getStatus() == InterventionStatus.PENDING) {
            intervention.setStatus(InterventionStatus.ASSIGNED);
        }
    }

    /**
     * Enregistre un diagnostic technique sur l'intervention (US45).
     */
    public MaintenanceIntervention addDiagnosis(String interventionId, DiagnosisCreateRequest request, String techId, String techName) {
        MaintenanceIntervention intervention = getInterventionById(interventionId);

        MaintenanceDiagnosis diagnosis = new MaintenanceDiagnosis(
                request.getProbableCause(),
                request.getDetails(),
                techId,
                techName
        );

        intervention.getDiagnoses().add(diagnosis);
        if (intervention.getStatus() == InterventionStatus.PENDING || intervention.getStatus() == InterventionStatus.ASSIGNED) {
            intervention.setStatus(InterventionStatus.IN_PROGRESS);
            if (intervention.getStartedAt() == null) {
                intervention.setStartedAt(Instant.now());
            }
        }
        intervention.setUpdatedAt(Instant.now());

        MaintenanceIntervention saved = interventionRepository.save(intervention);
        log.info("Diagnostic ajouté sur l'intervention [{}] par [{}]", saved.getId(), techName);
        return saved;
    }

    /**
     * Enregistre une action réalisée sur l'intervention (US46).
     */
    public MaintenanceIntervention addAction(String interventionId, ActionCreateRequest request, String techId, String techName) {
        MaintenanceIntervention intervention = getInterventionById(interventionId);

        MaintenanceAction action = new MaintenanceAction(
                request.getDescription(),
                techId,
                techName,
                request.getPartsReplaced(),
                request.getDurationMinutes()
        );

        intervention.getActions().add(action);
        if (intervention.getStatus() == InterventionStatus.PENDING || intervention.getStatus() == InterventionStatus.ASSIGNED) {
            intervention.setStatus(InterventionStatus.IN_PROGRESS);
            if (intervention.getStartedAt() == null) {
                intervention.setStartedAt(Instant.now());
            }
        }
        intervention.setUpdatedAt(Instant.now());

        MaintenanceIntervention saved = interventionRepository.save(intervention);
        log.info("Action ajoutée sur l'intervention [{}] par [{}]", saved.getId(), techName);
        return saved;
    }

    /**
     * Ajoute une observation sur l'intervention (US47).
     */
    public MaintenanceIntervention addObservation(String interventionId, ObservationCreateRequest request, String authorId, String authorName) {
        MaintenanceIntervention intervention = getInterventionById(interventionId);

        MaintenanceObservation obs = new MaintenanceObservation(
                request.getContent(),
                authorId,
                authorName,
                request.getCategory()
        );

        intervention.getObservations().add(obs);
        intervention.setUpdatedAt(Instant.now());

        MaintenanceIntervention saved = interventionRepository.save(intervention);
        log.info("Observation ajoutée sur l'intervention [{}] par [{}]", saved.getId(), authorName);
        return saved;
    }

    /**
     * Clôture formelle d'une intervention de maintenance (US48).
     */
    public MaintenanceIntervention closeIntervention(String interventionId, InterventionCloseRequest request, String closedBy) {
        MaintenanceIntervention intervention = getInterventionById(interventionId);

        if (intervention.getStatus() == InterventionStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "L'intervention est déjà clôturée");
        }
        if (intervention.getStatus() == InterventionStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Impossible de clôturer une intervention annulée");
        }

        // Validation métier préalable : au moins un diagnostic ou une action doit avoir été saisi
        if (intervention.getDiagnoses().isEmpty() && intervention.getActions().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR: L'intervention doit comporter au moins un diagnostic ou une action enregistrée avant clôture");
        }

        intervention.setStatus(InterventionStatus.COMPLETED);
        intervention.setClosedAt(Instant.now());
        intervention.setClosedBy(closedBy != null ? closedBy : "SYSTEM");
        intervention.setClosureNotes(request != null ? request.getClosureNotes() : null);
        intervention.setUpdatedAt(Instant.now());

        // Résolution automatique de l'alerte liée si présente
        if (StringUtils.hasText(intervention.getAlertId())) {
            try {
                alertService.resolveAlert(intervention.getAlertId(), closedBy, "Clôturée suite à l'intervention " + intervention.getId());
            } catch (Exception ex) {
                log.warn("Résolution de l'alerte liée [{}] non bloquante : {}", intervention.getAlertId(), ex.getMessage());
            }
        }

        // Résolution de l'anomalie liée si présente
        if (StringUtils.hasText(intervention.getAnomalyId())) {
            anomalyRepository.findById(intervention.getAnomalyId()).ifPresent(anomaly -> {
                anomaly.setStatus("RESOLVED");
                anomalyRepository.save(anomaly);
            });
        }

        // Rétablissement éventuel de la machine après validation technique
        if (request != null && request.isRestoreMachineStatus()) {
            machineRepository.findById(intervention.getMachineId()).ifPresent(m -> {
                if (m.getStatus() == MachineStatus.MAINTENANCE || m.getStatus() == MachineStatus.FAILURE) {
                    m.setStatus(MachineStatus.RUNNING);
                    machineRepository.save(m);
                    log.info("Statut machine [{}] rétabli à RUNNING suite à la clôture de maintenance", m.getCode());
                }
            });
        }

        MaintenanceIntervention saved = interventionRepository.save(intervention);
        log.info("Intervention [{}] clôturée avec succès par [{}]", saved.getId(), closedBy);
        return saved;
    }

    /**
     * Consultation des détails de l'anomalie et mesures contextuelles autour de l'incident (US44).
     */
    public Map<String, Object> getAnomalyContext(String anomalyIdOrInterventionId) {
        Anomaly anomaly = null;
        MaintenanceIntervention intervention = null;

        Optional<MaintenanceIntervention> interOpt = interventionRepository.findById(anomalyIdOrInterventionId);
        if (interOpt.isPresent()) {
            intervention = interOpt.get();
            if (StringUtils.hasText(intervention.getAnomalyId())) {
                anomaly = anomalyRepository.findById(intervention.getAnomalyId()).orElse(null);
            }
        } else {
            anomaly = anomalyRepository.findById(anomalyIdOrInterventionId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Anomalie ou intervention introuvable : " + anomalyIdOrInterventionId));
        }

        if (anomaly == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Aucune anomalie rattachée à cette intervention");
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("anomalyId", anomaly.getId());
        context.put("machineId", anomaly.getMachineId());
        context.put("machineCode", anomaly.getMachineCode());
        context.put("sensorId", anomaly.getSensorId());
        context.put("sensorType", anomaly.getSensorType() != null ? anomaly.getSensorType().name() : null);
        context.put("unit", anomaly.getSensorType() != null ? anomaly.getSensorType().getDefaultUnit() : null);
        context.put("observedValue", anomaly.getObservedValue());
        context.put("thresholdValue", anomaly.getThresholdValue());
        context.put("severity", anomaly.getSeverity());
        context.put("status", anomaly.getStatus());
        context.put("occurrenceCount", anomaly.getOccurrenceCount());
        context.put("incidentTimestamp", anomaly.getTimestamp().toString());
        context.put("message", anomaly.getMessage());

        if (StringUtils.hasText(anomaly.getAlertId())) {
            alertRepository.findById(anomaly.getAlertId()).ifPresent(alert -> {
                context.put("alert", Map.of(
                        "id", alert.getId(),
                        "severity", alert.getSeverity(),
                        "status", alert.getStatus(),
                        "acknowledgedBy", alert.getAcknowledgedBy() != null ? alert.getAcknowledgedBy() : "",
                        "acknowledgedAt", alert.getAcknowledgedAt() != null ? alert.getAcknowledgedAt().toString() : ""
                ));
            });
        }

        // Récupération des mesures contextuelles autour de l'incident (±10 minutes) depuis la base hebdomadaire ciblée
        Instant from = anomaly.getTimestamp().minus(10, ChronoUnit.MINUTES);
        Instant to = anomaly.getTimestamp().plus(10, ChronoUnit.MINUTES);

        List<String> sensorIds = StringUtils.hasText(anomaly.getSensorId())
                ? List.of(anomaly.getSensorId())
                : Collections.emptyList();

        List<SensorReading> surrounding = weeklyHistoryDatabaseService.findReadingsInWeeklyDatabases(
                sensorIds, anomaly.getSensorType(), from, to, null, 100
        );

        List<Map<String, Object>> readingsData = new ArrayList<>();
        for (SensorReading r : surrounding) {
            readingsData.add(Map.of(
                    "timestamp", r.getTimestamp().toString(),
                    "value", r.getValue(),
                    "unit", r.getUnit()
            ));
        }
        context.put("surroundingReadings", readingsData);
        context.put("surroundingWindowMinutes", 10);

        return context;
    }

    /**
     * Consultation de l'historique complet des interventions avec filtres et pagination (US49).
     */
    public Page<MaintenanceIntervention> getInterventions(String machineId,
                                                          String technicianId,
                                                          MaintenanceType type,
                                                          PreventiveSubtype subtype,
                                                          InterventionStatus status,
                                                          Instant from,
                                                          Instant to,
                                                          Pageable pageable) {
        Query query = new Query();
        List<Criteria> criteriaList = new ArrayList<>();

        if (StringUtils.hasText(machineId)) {
            criteriaList.add(new Criteria().orOperator(
                    Criteria.where("machineId").is(machineId.trim()),
                    Criteria.where("machineCode").is(machineId.trim())
            ));
        }
        if (StringUtils.hasText(technicianId)) {
            criteriaList.add(Criteria.where("technicianId").is(technicianId.trim()));
        }
        if (type != null) {
            criteriaList.add(Criteria.where("type").is(type));
        }
        if (subtype != null) {
            criteriaList.add(Criteria.where("preventiveSubtype").is(subtype));
        }
        if (status != null) {
            criteriaList.add(Criteria.where("status").is(status));
        }
        if (from != null && to != null) {
            criteriaList.add(Criteria.where("createdAt").gte(from).lte(to));
        } else if (from != null) {
            criteriaList.add(Criteria.where("createdAt").gte(from));
        } else if (to != null) {
            criteriaList.add(Criteria.where("createdAt").lte(to));
        }

        if (!criteriaList.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(criteriaList.toArray(new Criteria[0])));
        }

        long total = mongoTemplate.count(query, MaintenanceIntervention.class);
        query.with(pageable);
        List<MaintenanceIntervention> content = mongoTemplate.find(query, MaintenanceIntervention.class);

        return new PageImpl<>(content, pageable, total);
    }

    public MaintenanceIntervention getInterventionById(String id) {
        return interventionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Intervention introuvable : " + id));
    }
}
