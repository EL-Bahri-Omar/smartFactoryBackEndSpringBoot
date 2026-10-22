package com.smartfactory.controller;

import com.smartfactory.entity.Alert;
import com.smartfactory.security.UserPrincipal;
import com.smartfactory.service.AlertService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * Contrôleur REST pour la consultation et la gestion des alertes (Sprint 5 - US37, US38, US39, US41).
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    /**
     * Consultation paginée des alertes avec filtres (US39, Section 8).
     * Accessible aux rôles ADMIN, RESPONSABLE_INDUSTRIEL, TECHNICIAN.
     * STRICTEMENT INTERDIT à l'OPERATOR (Section 8).
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'RESPONSABLE_INDUSTRIEL', 'TECHNICIAN') or (hasAuthority('ALERT_READ') and !hasRole('OPERATOR'))")
    public ResponseEntity<Page<Alert>> getAlerts(
            @RequestParam(required = false) String machineId,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "timestamp,desc") String[] sort) {

        Sort sortOrder = Sort.by(Sort.Direction.DESC, "timestamp");
        if (sort != null && sort.length > 0) {
            String property = sort[0];
            Sort.Direction direction = (sort.length > 1 && "asc".equalsIgnoreCase(sort[1])) ? Sort.Direction.ASC : Sort.Direction.DESC;
            sortOrder = Sort.by(direction, property);
        }

        Pageable pageable = PageRequest.of(page, size, sortOrder);
        Page<Alert> result = alertService.getAlerts(machineId, severity, status, from, to, pageable);
        return ResponseEntity.ok(result);
    }

    /**
     * Consultation d'une alerte par son identifiant.
     * STRICTEMENT INTERDIT à l'OPERATOR (Section 8).
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RESPONSABLE_INDUSTRIEL', 'TECHNICIAN') or (hasAuthority('ALERT_READ') and !hasRole('OPERATOR'))")
    public ResponseEntity<Alert> getAlertById(@PathVariable String id) {
        return ResponseEntity.ok(alertService.getAlertById(id));
    }

    /**
     * Résumé quantitatif des alertes pour le tableau de bord de supervision.
     * STRICTEMENT INTERDIT à l'OPERATOR (Section 8).
     */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'RESPONSABLE_INDUSTRIEL', 'TECHNICIAN') or (hasAuthority('ALERT_READ') and !hasRole('OPERATOR'))")
    public ResponseEntity<Map<String, Object>> getAlertSummary() {
        return ResponseEntity.ok(alertService.getAlertSummary());
    }

    /**
     * Acquittement formel d'une alerte par un technicien ou responsable (US41, Section 5).
     * Accepte POST et PUT sur /{id}/acknowledge ainsi que /{id}/read.
     */
    @RequestMapping(value = {"/{id}/acknowledge", "/{id}/read"}, method = {RequestMethod.POST, RequestMethod.PUT})
    @PreAuthorize("hasAnyRole('ADMIN', 'RESPONSABLE_INDUSTRIEL', 'TECHNICIAN') or hasAnyAuthority('ALERT_WRITE', 'ALERT:ACQUITTER', 'ALERT:MODIFIER')")
    public ResponseEntity<Alert> acknowledgeAlert(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> requestBody,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        String userId = currentUser != null ? (currentUser.getId() != null ? currentUser.getId() : currentUser.getUsername()) : "ANONYMOUS";
        String comment = requestBody != null ? requestBody.get("comment") : null;

        Alert acknowledged = alertService.acknowledgeAlert(id, userId, comment);
        return ResponseEntity.ok(acknowledged);
    }

    /**
     * Résolution d'une alerte après intervention.
     */
    @RequestMapping(value = "/{id}/resolve", method = {RequestMethod.POST, RequestMethod.PUT})
    @PreAuthorize("hasAnyRole('ADMIN', 'RESPONSABLE_INDUSTRIEL', 'TECHNICIAN') or hasAnyAuthority('ALERT_WRITE', 'ALERT:MODIFIER')")
    public ResponseEntity<Alert> resolveAlert(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> requestBody,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        String userId = currentUser != null ? (currentUser.getId() != null ? currentUser.getId() : currentUser.getUsername()) : "ANONYMOUS";
        String comment = requestBody != null ? requestBody.get("comment") : null;

        Alert resolved = alertService.resolveAlert(id, userId, comment);
        return ResponseEntity.ok(resolved);
    }
}
