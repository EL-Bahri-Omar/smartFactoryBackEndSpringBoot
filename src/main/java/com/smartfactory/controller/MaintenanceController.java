package com.smartfactory.controller;

import com.smartfactory.dto.*;
import com.smartfactory.entity.*;
import com.smartfactory.security.UserPrincipal;
import com.smartfactory.service.MaintenanceInterventionService;
import com.smartfactory.service.MaintenanceRecommendationService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Contrôleur REST pour la gestion intégrale de la maintenance et des interventions (Sprint 6 - US42 à US50).
 */
@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {

    private final MaintenanceInterventionService interventionService;
    private final MaintenanceRecommendationService recommendationService;

    public MaintenanceController(MaintenanceInterventionService interventionService,
                                 MaintenanceRecommendationService recommendationService) {
        this.interventionService = interventionService;
        this.recommendationService = recommendationService;
    }

    /**
     * Crée une intervention de maintenance (US42).
     */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('MAINTENANCE_WRITE', 'MAINTENANCE:AJOUTER', 'TECHNICIAN', 'RESPONSABLE_INDUSTRIEL', 'ROLE_ADMIN')")
    public ResponseEntity<MaintenanceIntervention> createIntervention(
            @Valid @RequestBody InterventionCreateRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        String username = currentUser != null ? currentUser.getUsername() : "ANONYMOUS";
        MaintenanceIntervention created = interventionService.createIntervention(request, username);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    /**
     * Consulte l'historique complet des interventions avec filtres et pagination (US49).
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('MAINTENANCE_READ', 'MAINTENANCE:CONSULTER', 'TECHNICIAN', 'RESPONSABLE_INDUSTRIEL', 'ROLE_ADMIN')")
    public ResponseEntity<Page<MaintenanceIntervention>> getInterventions(
            @RequestParam(required = false) String machineId,
            @RequestParam(required = false) String technicianId,
            @RequestParam(required = false) MaintenanceType type,
            @RequestParam(required = false) PreventiveSubtype subtype,
            @RequestParam(required = false) InterventionStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String[] sort) {

        Sort sortOrder = Sort.by(Sort.Direction.DESC, "createdAt");
        if (sort != null && sort.length > 0) {
            String property = sort[0];
            Sort.Direction dir = (sort.length > 1 && "asc".equalsIgnoreCase(sort[1])) ? Sort.Direction.ASC : Sort.Direction.DESC;
            sortOrder = Sort.by(dir, property);
        }

        Pageable pageable = PageRequest.of(page, size, sortOrder);
        Page<MaintenanceIntervention> result = interventionService.getInterventions(
                machineId, technicianId, type, subtype, status, from, to, pageable
        );
        return ResponseEntity.ok(result);
    }

    /**
     * Consulte le détail d'une intervention.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('MAINTENANCE_READ', 'MAINTENANCE:CONSULTER', 'TECHNICIAN', 'RESPONSABLE_INDUSTRIEL', 'ROLE_ADMIN')")
    public ResponseEntity<MaintenanceIntervention> getInterventionById(@PathVariable String id) {
        return ResponseEntity.ok(interventionService.getInterventionById(id));
    }

    /**
     * Assigne un technicien à une intervention (US43).
     */
    @PutMapping("/{id}/assign")
    @PreAuthorize("hasAnyAuthority('MAINTENANCE_WRITE', 'MAINTENANCE:MODIFIER', 'RESPONSABLE_INDUSTRIEL', 'ROLE_ADMIN')")
    public ResponseEntity<MaintenanceIntervention> assignTechnician(
            @PathVariable String id,
            @Valid @RequestBody InterventionAssignRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        String assignedBy = currentUser != null ? currentUser.getUsername() : "ANONYMOUS";
        MaintenanceIntervention updated = interventionService.assignTechnician(id, request.getTechnicianId(), assignedBy);
        return ResponseEntity.ok(updated);
    }

    /**
     * Consulte l'anomalie et les mesures contextuelles associées à l'intervention (US44).
     */
    @GetMapping("/{id}/anomaly")
    @PreAuthorize("hasAnyAuthority('MAINTENANCE_READ', 'MAINTENANCE:CONSULTER', 'TECHNICIAN', 'RESPONSABLE_INDUSTRIEL', 'ROLE_ADMIN')")
    public ResponseEntity<Map<String, Object>> getAnomalyContext(@PathVariable String id) {
        return ResponseEntity.ok(interventionService.getAnomalyContext(id));
    }

    /**
     * Enregistre un diagnostic technique (US45).
     */
    @PostMapping("/{id}/diagnoses")
    @PreAuthorize("hasAnyAuthority('MAINTENANCE_WRITE', 'MAINTENANCE:MODIFIER', 'TECHNICIAN', 'ROLE_ADMIN')")
    public ResponseEntity<MaintenanceIntervention> addDiagnosis(
            @PathVariable String id,
            @Valid @RequestBody DiagnosisCreateRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        String techId = currentUser != null ? currentUser.getUsername() : "ANONYMOUS";
        String techName = currentUser != null ? currentUser.getUsername() : "Technicien";
        MaintenanceIntervention updated = interventionService.addDiagnosis(id, request, techId, techName);
        return ResponseEntity.ok(updated);
    }

    /**
     * Enregistre une action de maintenance réalisée (US46).
     */
    @PostMapping("/{id}/actions")
    @PreAuthorize("hasAnyAuthority('MAINTENANCE_WRITE', 'MAINTENANCE:MODIFIER', 'TECHNICIAN', 'ROLE_ADMIN')")
    public ResponseEntity<MaintenanceIntervention> addAction(
            @PathVariable String id,
            @Valid @RequestBody ActionCreateRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        String techId = currentUser != null ? currentUser.getUsername() : "ANONYMOUS";
        String techName = currentUser != null ? currentUser.getUsername() : "Technicien";
        MaintenanceIntervention updated = interventionService.addAction(id, request, techId, techName);
        return ResponseEntity.ok(updated);
    }

    /**
     * Ajoute une observation technique (US47).
     */
    @PostMapping("/{id}/observations")
    @PreAuthorize("hasAnyAuthority('MAINTENANCE_WRITE', 'MAINTENANCE:MODIFIER', 'TECHNICIAN', 'RESPONSABLE_INDUSTRIEL', 'ROLE_ADMIN')")
    public ResponseEntity<MaintenanceIntervention> addObservation(
            @PathVariable String id,
            @Valid @RequestBody ObservationCreateRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        String authorId = currentUser != null ? currentUser.getUsername() : "ANONYMOUS";
        String authorName = currentUser != null ? currentUser.getUsername() : "Auteur";
        MaintenanceIntervention updated = interventionService.addObservation(id, request, authorId, authorName);
        return ResponseEntity.ok(updated);
    }

    /**
     * Clôture définitive d'une intervention (US48).
     */
    @PutMapping("/{id}/close")
    @PreAuthorize("hasAnyAuthority('MAINTENANCE_WRITE', 'MAINTENANCE:MODIFIER', 'TECHNICIAN', 'RESPONSABLE_INDUSTRIEL', 'ROLE_ADMIN')")
    public ResponseEntity<MaintenanceIntervention> closeIntervention(
            @PathVariable String id,
            @Valid @RequestBody InterventionCloseRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        String closedBy = currentUser != null ? currentUser.getUsername() : "ANONYMOUS";
        MaintenanceIntervention closed = interventionService.closeIntervention(id, request, closedBy);
        return ResponseEntity.ok(closed);
    }

    /**
     * Formule des recommandations d'interventions basées sur les données machines (US50).
     */
    @GetMapping("/recommendations/{machineId}")
    @PreAuthorize("hasAnyAuthority('MAINTENANCE_READ', 'MAINTENANCE:CONSULTER', 'TECHNICIAN', 'RESPONSABLE_INDUSTRIEL', 'ROLE_ADMIN')")
    public ResponseEntity<List<MaintenanceRecommendationService.Recommendation>> getRecommendations(
            @PathVariable String machineId) {

        List<MaintenanceRecommendationService.Recommendation> recs = recommendationService.generateRecommendations(machineId);
        return ResponseEntity.ok(recs);
    }

    /**
     * Convertit et applique une recommandation en intervention de maintenance réelle (US50).
     */
    @PostMapping("/recommendations/{machineId}/apply")
    @PreAuthorize("hasAnyAuthority('MAINTENANCE_WRITE', 'MAINTENANCE:AJOUTER', 'TECHNICIAN', 'RESPONSABLE_INDUSTRIEL', 'ROLE_ADMIN')")
    public ResponseEntity<MaintenanceIntervention> applyRecommendation(
            @PathVariable String machineId,
            @RequestParam(defaultValue = "0") int index,
            @RequestParam(required = false) String technicianId,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        String username = currentUser != null ? currentUser.getUsername() : "ANONYMOUS";
        MaintenanceIntervention created = recommendationService.createFromRecommendation(machineId, index, technicianId, username);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }
}
