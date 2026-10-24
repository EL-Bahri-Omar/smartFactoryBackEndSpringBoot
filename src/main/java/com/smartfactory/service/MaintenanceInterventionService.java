package com.smartfactory.service;

import com.smartfactory.dto.*;
import com.smartfactory.entity.*;
import com.smartfactory.repository.*;
import com.smartfactory.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
import java.util.stream.Collectors;

/**
 * Service de gestion complète des interventions de maintenance (Sprint 6 - US42 à US50).
 *
 * Fonctionnalités couvertes :
 * - Création, planification et clôture d'interventions avec typologie stricte (CORRECTIVE, PREVENTIVE, IMPROVEMENT).
 * - Affectation de plusieurs techniciens par intervention (technicianIds) avec déduplication et vérification des rôles.
 * - Migration automatique transparente des documents MongoDB legacy contenant technicianId sans perte de données.
 * - Notifications persistantes au responsable créateur lors de la modification ou clôture par un technicien.
 * - Rétrocompatibilité totale avec les anciens contrats d'API et les recherches multicritères.
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
     * Migration automatique à chaud des interventions MongoDB existantes contenant technicianId.
     * Garantit qu'aucun document n'est supprimé ni corrompu.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void migrateLegacyTechnicianIdData() {
        try {
            Query query = new Query(Criteria.where("technicianId").ne(null)
                    .andOperator(new Criteria().orOperator(
                            Criteria.where("technicianIds").is(null),
                            Criteria.where("technicianIds").size(0)
                    )));
            List<MaintenanceIntervention> legacyList = mongoTemplate.find(query, MaintenanceIntervention.class);
            if (!legacyList.isEmpty()) {
                log.info("Migration MongoDB : {} intervention(s) contenant technicianId détectée(s)...", legacyList.size());
                for (MaintenanceIntervention interv : legacyList) {
                    if (StringUtils.hasText(interv.getTechnicianId())) {
                        interv.getTechnicianIds(); // Auto-synchronise technicianIds
                        if (interv.getTechnicianNames().isEmpty() && StringUtils.hasText(interv.getTechnicianName())) {
                            interv.getTechnicianNames().add(interv.getTechnicianName());
                        }
                        interventionRepository.save(interv);
                    }
                }
                log.info("Migration MongoDB terminée avec succès (0 donnée perdue).");
            }
        } catch (Exception ex) {
            log.warn("Migration MongoDB interventions non bloquante : {}", ex.getMessage());
        }
    }

    /**
     * Crée une nouvelle intervention de maintenance (US42, Section 6.1).
     * RÈGLE STRICTE : Seul le RESPONSABLE_INDUSTRIEL et l'ADMIN peuvent créer une intervention.
     */
    public MaintenanceIntervention createIntervention(InterventionCreateRequest request, String currentUsername, com.smartfactory.security.UserPrincipal currentUser) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Requête obligatoire");
        }

        // Section 6.1 : Vérification côté serveur des rôles de création
        if (currentUser != null) {
            if (currentUser.getRole() == Role.OPERATOR || currentUser.getRole() == Role.TECHNICIAN) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Accès refusé : Seul le rôle RESPONSABLE_INDUSTRIEL est autorisé à créer une intervention");
            }
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
        intervention.setCreatedBy(currentUsername != null ? currentUsername : "SYSTEM");

        // 4. Liaison éventuelle avec une alerte ou anomalie existante
        if (StringUtils.hasText(request.getAlertId())) {
            Alert alert = alertRepository.findById(request.getAlertId().trim())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alerte introuvable : " + request.getAlertId()));
            intervention.setAlertId(alert.getId());
            if (StringUtils.hasText(alert.getAnomalyId())) {
                intervention.setAnomalyId(alert.getAnomalyId());
            }
            if (request.getPriority() == null && "CRITICAL".equalsIgnoreCase(alert.getSeverity())) {
                intervention.setPriority(InterventionPriority.CRITICAL);
            } else if (request.getPriority() == null && "HIGH".equalsIgnoreCase(alert.getSeverity())) {
                intervention.setPriority(InterventionPriority.HIGH);
            }
        }

        if (StringUtils.hasText(request.getAnomalyId())) {
            intervention.setAnomalyId(request.getAnomalyId().trim());
        }

        // 5. Affectation initiale d'un ou plusieurs techniciens si spécifié (Exigence 6)
        List<String> requestedTechIds = request.getTechnicianIds();
        if (requestedTechIds != null && !requestedTechIds.isEmpty()) {
            assignTechniciansInternal(intervention, requestedTechIds, currentUsername, true);
        } else if (StringUtils.hasText(request.getTechnicianId())) {
            assignTechniciansInternal(intervention, List.of(request.getTechnicianId().trim()), currentUsername, true);
        }

        MaintenanceIntervention saved = interventionRepository.save(intervention);
        log.info("Intervention de maintenance créée [{}] ({}) sur machine [{}] par [{}] affectée à {} technicien(s)",
                saved.getId(), saved.getType(), machine.getCode(), currentUsername, saved.getTechnicianIds().size());
        return saved;
    }

    public MaintenanceIntervention createIntervention(InterventionCreateRequest request, String currentUsername) {
        return createIntervention(request, currentUsername, null);
    }

    /**
     * Assigne une liste de techniciens à une intervention (US43, Exigence 6).
     */
    public MaintenanceIntervention assignTechnicians(String interventionId, List<String> technicianIds, String assignedBy) {
        MaintenanceIntervention intervention = getInterventionById(interventionId);
        assignTechniciansInternal(intervention, technicianIds, assignedBy, true);
        MaintenanceIntervention saved = interventionRepository.save(intervention);
        log.info("Intervention [{}] assignée aux techniciens {} par [{}]", saved.getId(), saved.getTechnicianIds(), assignedBy);
        return saved;
    }

    /**
     * Assigne un technicien éligible unique (Rétrocompatibilité ascendante).
     */
    public MaintenanceIntervention assignTechnician(String interventionId, String technicianId, String assignedBy) {
        List<String> list = StringUtils.hasText(technicianId) ? List.of(technicianId.trim()) : Collections.emptyList();
        return assignTechnicians(interventionId, list, assignedBy);
    }

    private void assignTechniciansInternal(MaintenanceIntervention intervention, List<String> technicianIds, String assignedBy, boolean dispatchNotification) {
        if (technicianIds == null || technicianIds.isEmpty()) {
            intervention.setTechnicianIds(new ArrayList<>());
            intervention.setTechnicianNames(new ArrayList<>());
            return;
        }

        List<String> previousTechIds = intervention.getTechnicianIds() != null
                ? new ArrayList<>(intervention.getTechnicianIds())
                : new ArrayList<>();

        // Déduplication stricte et validation des techniciens affectés
        List<String> distinctTechIds = technicianIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .collect(Collectors.toList());

        List<User> validatedTechnicians = new ArrayList<>();
        for (String techId : distinctTechIds) {
            User technician = userRepository.findById(techId)
                    .or(() -> userRepository.findByEmail(techId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Technicien introuvable : " + techId));

            boolean isEligible = technician.getRole() == Role.TECHNICIAN
                    || technician.getRole() == Role.RESPONSABLE_INDUSTRIEL
                    || technician.getRole() == Role.ADMIN
                    || (technician.getEffectivePermissions() != null
                    && technician.getEffectivePermissions().contains(com.smartfactory.security.Permission.MAINTENANCE_WRITE));

            if (!isEligible) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "L'utilisateur [" + technician.getEmail() + "] n'a pas les droits nécessaires pour intervenir sur la maintenance");
            }
            validatedTechnicians.add(technician);
        }

        List<String> finalTechIds = validatedTechnicians.stream().map(User::getId).collect(Collectors.toList());
        List<String> finalTechNames = validatedTechnicians.stream()
                .map(u -> u.getFirstName() != null ? (u.getFirstName() + " " + u.getLastName()) : u.getEmail())
                .collect(Collectors.toList());

        intervention.setTechnicianIds(finalTechIds);
        intervention.setTechnicianNames(finalTechNames);
        intervention.setAssignedBy(assignedBy != null ? assignedBy : "SYSTEM");
        intervention.setAssignedAt(Instant.now());

        if (intervention.getStatus() == InterventionStatus.PENDING && !finalTechIds.isEmpty()) {
            intervention.setStatus(InterventionStatus.ASSIGNED);
        }

        if (dispatchNotification) {
            // Notifier chaque technicien nouvellement assigné
            for (User tech : validatedTechnicians) {
                if (!previousTechIds.contains(tech.getId())) {
                    try {
                        notificationService.notifyMaintenanceAssigned(
                                tech.getId(),
                                intervention.getId(),
                                intervention.getTitle(),
                                intervention.getMachineCode(),
                                intervention.getType() != null ? intervention.getType().name() : null,
                                intervention.getPreventiveSubtype() != null ? intervention.getPreventiveSubtype().name() : null,
                                intervention.getPriority() != null ? intervention.getPriority().name() : null,
                                intervention.getAssignedAt()
                        );
                    } catch (Exception ex) {
                        log.warn("Notification d'assignation échouée pour [{}] : {}", tech.getId(), ex.getMessage());
                    }
                }
            }

            // Notifier les anciens techniciens retirés de l'intervention
            for (String prevId : previousTechIds) {
                if (!finalTechIds.contains(prevId)) {
                    try {
                        notificationService.notifyMaintenanceReassigned(
                                prevId,
                                intervention.getId(),
                                intervention.getTitle(),
                                String.join(", ", finalTechNames)
                        );
                    } catch (Exception ex) {
                        log.warn("Notification de réassignation échouée pour [{}] : {}", prevId, ex.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Modification des informations de gestion et planification (Section 6.2).
     */
    public MaintenanceIntervention updateIntervention(String interventionId, com.smartfactory.dto.InterventionUpdateRequest request, String updatedBy, com.smartfactory.security.UserPrincipal currentUser) {
        if (currentUser != null && (currentUser.getRole() == Role.TECHNICIAN || currentUser.getRole() == Role.OPERATOR)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Accès refusé : Seul le rôle RESPONSABLE_INDUSTRIEL est autorisé à modifier les informations de gestion");
        }

        MaintenanceIntervention intervention = getInterventionById(interventionId);
        if (request == null) {
            return intervention;
        }

        if (StringUtils.hasText(request.getTitle())) {
            intervention.setTitle(request.getTitle().trim());
        }
        if (request.getDescription() != null) {
            intervention.setDescription(request.getDescription());
        }
        if (request.getPriority() != null) {
            intervention.setPriority(request.getPriority());
        }
        if (request.getStatus() != null) {
            intervention.setStatus(request.getStatus());
        }
        if (request.getScheduledAt() != null) {
            intervention.setScheduledAt(request.getScheduledAt());
        }

        // Modification des techniciens affectés (liste prioritaire ou id simple)
        if (request.getTechnicianIds() != null) {
            assignTechniciansInternal(intervention, request.getTechnicianIds(), updatedBy, true);
        } else if (StringUtils.hasText(request.getTechnicianId())) {
            assignTechniciansInternal(intervention, List.of(request.getTechnicianId().trim()), updatedBy, true);
        }

        intervention.setUpdatedAt(Instant.now());
        MaintenanceIntervention saved = interventionRepository.save(intervention);
        log.info("Intervention [{}] mise à jour par [{}]", saved.getId(), updatedBy);
        return saved;
    }

    /**
     * Contrôle d'accès strict pour l'exécution d'interventions par un technicien (Section 6.2).
     * Vérifie que le technicien fait partie de la liste technicianIds.
     */
    public void checkTechnicianExecutionAccess(MaintenanceIntervention intervention, com.smartfactory.security.UserPrincipal currentUser, String action) {
        if (currentUser == null) return;
        if (currentUser.getRole() == Role.OPERATOR) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "L'opérateur n'est pas autorisé à intervenir sur la maintenance");
        }
        if (currentUser.getRole() == Role.TECHNICIAN) {
            String currentId = currentUser.getId();
            String currentEmail = currentUser.getUsername();

            List<String> assignedTechs = intervention != null ? intervention.getTechnicianIds() : Collections.emptyList();
            boolean assigned = assignedTechs.stream().anyMatch(t ->
                    (currentId != null && t.equalsIgnoreCase(currentId)) ||
                    (currentEmail != null && t.equalsIgnoreCase(currentEmail)));

            // Rétrocompatibilité avec champ legacy technicianId
            if (!assigned && intervention != null && StringUtils.hasText(intervention.getTechnicianId())) {
                assigned = intervention.getTechnicianId().equalsIgnoreCase(currentId)
                        || intervention.getTechnicianId().equalsIgnoreCase(currentEmail);
            }

            if (!assigned) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Accès refusé : un technicien ne peut pas " + action + " une intervention qui ne lui est pas assignée");
            }
        }
    }

    /**
     * Contrôle de consultation pour interventions (Section 6.3).
     */
    public void checkConsultationAccess(MaintenanceIntervention intervention, com.smartfactory.security.UserPrincipal currentUser) {
        if (currentUser == null) return;
        if (currentUser.getRole() == Role.OPERATOR) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "L'opérateur n'est pas autorisé à consulter les interventions");
        }
        if (currentUser.getRole() == Role.TECHNICIAN) {
            String currentId = currentUser.getId();
            String currentEmail = currentUser.getUsername();

            List<String> assignedTechs = intervention != null ? intervention.getTechnicianIds() : Collections.emptyList();
            boolean assigned = assignedTechs.stream().anyMatch(t ->
                    (currentId != null && t.equalsIgnoreCase(currentId)) ||
                    (currentEmail != null && t.equalsIgnoreCase(currentEmail)));

            if (!assigned && intervention != null && StringUtils.hasText(intervention.getTechnicianId())) {
                assigned = intervention.getTechnicianId().equalsIgnoreCase(currentId)
                        || intervention.getTechnicianId().equalsIgnoreCase(currentEmail);
            }

            if (!assigned) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Accès refusé : l'intervention demandée n'est pas assignée à ce technicien");
            }
        }
    }

    /**
     * Enregistre un diagnostic technique sur l'intervention (US45, Section 6.2).
     * Notifie le responsable créateur (Exigence 5).
     */
    public MaintenanceIntervention addDiagnosis(String interventionId, DiagnosisCreateRequest request, String techId, String techName, com.smartfactory.security.UserPrincipal currentUser) {
        MaintenanceIntervention intervention = getInterventionById(interventionId);
        checkTechnicianExecutionAccess(intervention, currentUser, "ajouter un diagnostic sur");

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

        // Notification vers le responsable créateur (Exigence 5)
        if (StringUtils.hasText(saved.getCreatedBy())) {
            try {
                notificationService.notifyTechnicianModifiedIntervention(
                        saved.getCreatedBy(),
                        saved.getId(),
                        saved.getTitle(),
                        saved.getMachineCode(),
                        techId,
                        techName,
                        "Diagnostic : " + request.getProbableCause()
                );
            } catch (Exception ex) {
                log.warn("Échec notification modification responsable créateur : {}", ex.getMessage());
            }
        }

        return saved;
    }

    public MaintenanceIntervention addDiagnosis(String interventionId, DiagnosisCreateRequest request, String techId, String techName) {
        return addDiagnosis(interventionId, request, techId, techName, null);
    }

    /**
     * Enregistre une action réalisée sur l'intervention (US46, Section 6.2).
     * Notifie le responsable créateur (Exigence 5).
     */
    public MaintenanceIntervention addAction(String interventionId, ActionCreateRequest request, String techId, String techName, com.smartfactory.security.UserPrincipal currentUser) {
        MaintenanceIntervention intervention = getInterventionById(interventionId);
        checkTechnicianExecutionAccess(intervention, currentUser, "ajouter une action sur");

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

        // Notification vers le responsable créateur (Exigence 5)
        if (StringUtils.hasText(saved.getCreatedBy())) {
            try {
                notificationService.notifyTechnicianModifiedIntervention(
                        saved.getCreatedBy(),
                        saved.getId(),
                        saved.getTitle(),
                        saved.getMachineCode(),
                        techId,
                        techName,
                        "Action : " + request.getDescription()
                );
            } catch (Exception ex) {
                log.warn("Échec notification modification responsable créateur : {}", ex.getMessage());
            }
        }

        return saved;
    }

    public MaintenanceIntervention addAction(String interventionId, ActionCreateRequest request, String techId, String techName) {
        return addAction(interventionId, request, techId, techName, null);
    }

    /**
     * Ajoute une observation sur l'intervention (US47, Section 6.2).
     * Notifie le responsable créateur (Exigence 5).
     */
    public MaintenanceIntervention addObservation(String interventionId, ObservationCreateRequest request, String authorId, String authorName, com.smartfactory.security.UserPrincipal currentUser) {
        MaintenanceIntervention intervention = getInterventionById(interventionId);
        checkTechnicianExecutionAccess(intervention, currentUser, "ajouter une observation sur");

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

        // Notification vers le responsable créateur (Exigence 5)
        if (StringUtils.hasText(saved.getCreatedBy())) {
            try {
                notificationService.notifyTechnicianModifiedIntervention(
                        saved.getCreatedBy(),
                        saved.getId(),
                        saved.getTitle(),
                        saved.getMachineCode(),
                        authorId,
                        authorName,
                        "Observation : " + request.getContent()
                );
            } catch (Exception ex) {
                log.warn("Échec notification modification responsable créateur : {}", ex.getMessage());
            }
        }

        return saved;
    }

    public MaintenanceIntervention addObservation(String interventionId, ObservationCreateRequest request, String authorId, String authorName) {
        return addObservation(interventionId, request, authorId, authorName, null);
    }

    /**
     * Clôture formelle d'une intervention de maintenance (US48, Section 6.2).
     * Notifie spécifiquement le responsable créateur (Exigence 5).
     */
    public MaintenanceIntervention closeIntervention(String interventionId, InterventionCloseRequest request, String closedBy, com.smartfactory.security.UserPrincipal currentUser) {
        MaintenanceIntervention intervention = getInterventionById(interventionId);
        checkTechnicianExecutionAccess(intervention, currentUser, "clôturer");

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

        // Notification au responsable créateur (Exigence 5)
        if (StringUtils.hasText(saved.getCreatedBy())) {
            try {
                notificationService.notifyTechnicianCompletedIntervention(
                        saved.getCreatedBy(),
                        saved.getId(),
                        saved.getTitle(),
                        saved.getMachineCode(),
                        closedBy,
                        closedBy,
                        request != null ? request.getClosureNotes() : null
                );
            } catch (Exception ex) {
                log.warn("Échec notification clôture responsable créateur : {}", ex.getMessage());
            }
        }

        return saved;
    }

    public MaintenanceIntervention closeIntervention(String interventionId, InterventionCloseRequest request, String closedBy) {
        return closeIntervention(interventionId, request, closedBy, null);
    }

    /**
     * Consultation des détails de l'anomalie et mesures contextuelles autour de l'incident (US44, Section 6.3).
     */
    public Map<String, Object> getAnomalyContext(String anomalyIdOrInterventionId, com.smartfactory.security.UserPrincipal currentUser) {
        if (currentUser != null && currentUser.getRole() == Role.OPERATOR) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "L'opérateur n'est pas autorisé à consulter le contexte d'incident");
        }

        Anomaly anomaly = null;
        MaintenanceIntervention intervention = null;

        Optional<MaintenanceIntervention> interOpt = interventionRepository.findById(anomalyIdOrInterventionId);
        if (interOpt.isPresent()) {
            intervention = interOpt.get();
            checkConsultationAccess(intervention, currentUser);
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

    public Map<String, Object> getAnomalyContext(String anomalyIdOrInterventionId) {
        return getAnomalyContext(anomalyIdOrInterventionId, null);
    }

    /**
     * Consultation de l'historique complet des interventions avec filtres et pagination (US49, Section 6.3).
     * Prend en compte la recherche par technicien affecté (technicianIds) et la rétrocompatibilité (technicianId).
     */
    public Page<MaintenanceIntervention> getInterventions(String machineId,
                                                          String technicianId,
                                                          MaintenanceType type,
                                                          PreventiveSubtype subtype,
                                                          InterventionStatus status,
                                                          Instant from,
                                                          Instant to,
                                                          Pageable pageable,
                                                          com.smartfactory.security.UserPrincipal currentUser) {
        if (currentUser != null) {
            if (currentUser.getRole() == Role.OPERATOR) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "L'opérateur n'est pas autorisé à consulter les interventions");
            }
            if (currentUser.getRole() == Role.TECHNICIAN) {
                // Un technicien ne peut voir QUE ses propres interventions assignées (Section 6.3)
                technicianId = currentUser.getId();
            }
        }

        Query query = new Query();
        List<Criteria> criteriaList = new ArrayList<>();

        if (StringUtils.hasText(machineId)) {
            criteriaList.add(new Criteria().orOperator(
                    Criteria.where("machineId").is(machineId.trim()),
                    Criteria.where("machineCode").is(machineId.trim())
            ));
        }

        if (currentUser != null && currentUser.getRole() == Role.TECHNICIAN) {
            criteriaList.add(new Criteria().orOperator(
                    Criteria.where("technicianIds").is(currentUser.getId()),
                    Criteria.where("technicianIds").is(currentUser.getUsername()),
                    Criteria.where("technicianId").is(currentUser.getId()),
                    Criteria.where("technicianId").is(currentUser.getUsername())
            ));
        } else if (StringUtils.hasText(technicianId)) {
            criteriaList.add(new Criteria().orOperator(
                    Criteria.where("technicianIds").is(technicianId.trim()),
                    Criteria.where("technicianId").is(technicianId.trim())
            ));
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

    public Page<MaintenanceIntervention> getInterventions(String machineId,
                                                          String technicianId,
                                                          MaintenanceType type,
                                                          PreventiveSubtype subtype,
                                                          InterventionStatus status,
                                                          Instant from,
                                                          Instant to,
                                                          Pageable pageable) {
        return getInterventions(machineId, technicianId, type, subtype, status, from, to, pageable, null);
    }

    public MaintenanceIntervention getInterventionById(String id, com.smartfactory.security.UserPrincipal currentUser) {
        MaintenanceIntervention intervention = interventionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Intervention introuvable : " + id));
        checkConsultationAccess(intervention, currentUser);
        return intervention;
    }

    public MaintenanceIntervention getInterventionById(String id) {
        return getInterventionById(id, null);
    }
}
