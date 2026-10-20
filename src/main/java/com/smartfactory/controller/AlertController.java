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
     * Consultation paginée des alertes avec filtres (US39).
     * Accessible aux rôles OPERATOR, TECHNICIAN, RESPONSABLE_INDUSTRIEL, ADMIN.
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ALERT_READ', 'ALERT:CONSULTER')")
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
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ALERT_READ', 'ALERT:CONSULTER')")
    public ResponseEntity<Alert> getAlertById(@PathVariable String id) {
        return ResponseEntity.ok(alertService.getAlertById(id));
    }

    /**
     * Résumé quantitatif des alertes pour le tableau de bord de supervision.
     */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyAuthority('ALERT_READ', 'ALERT:CONSULTER')")
    public ResponseEntity<Map<String, Object>> getAlertSummary() {
        return ResponseEntity.ok(alertService.getAlertSummary());
    }

    /**
     * Acquittement formel d'une alerte par un technicien ou responsable (US41).
     */
    @PostMapping("/{id}/acknowledge")
    @PreAuthorize("hasAnyAuthority('ALERT_WRITE', 'ALERT:ACQUITTER', 'ALERT:MODIFIER')")
    public ResponseEntity<Alert> acknowledgeAlert(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> requestBody,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        String userId = currentUser != null ? currentUser.getUsername() : "ANONYMOUS";
        String comment = requestBody != null ? requestBody.get("comment") : null;

        Alert acknowledged = alertService.acknowledgeAlert(id, userId, comment);
        return ResponseEntity.ok(acknowledged);
    }

    /**
     * Résolution d'une alerte après intervention.
     */
    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAnyAuthority('ALERT_WRITE', 'ALERT:MODIFIER')")
    public ResponseEntity<Alert> resolveAlert(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> requestBody,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        String userId = currentUser != null ? currentUser.getUsername() : "ANONYMOUS";
        String comment = requestBody != null ? requestBody.get("comment") : null;

        Alert resolved = alertService.resolveAlert(id, userId, comment);
        return ResponseEntity.ok(resolved);
    }
}
