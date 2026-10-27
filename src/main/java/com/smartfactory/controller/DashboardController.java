package com.smartfactory.controller;

import com.smartfactory.service.DashboardService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Contrôleur REST - Dashboard & Supervision Industrielle (Sprint 7).
 * Expose les endpoints US51 à US55 pour le suivi temps réel et KPIs de l'usine.
 */
@RestController
@RequestMapping("/api/dashboard")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ADMIN', 'ROLE_RESPONSABLE_INDUSTRIEL', 'RESPONSABLE_INDUSTRIEL', 'ROLE_TECHNICIAN', 'TECHNICIAN')")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /**
     * US51: Vue d'ensemble du Dashboard industriel.
     * Synthèse globale : parc machines, alertes actives, anomalies, santé globale et état usine.
     */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> getOverview() {
        return ResponseEntity.ok(dashboardService.getOverview());
    }

    /**
     * US52: Indicateurs clés de performance industrielle (KPIs).
     * Disponibilité %, MTBF, MTTR, et TRS/OEE (avec statut UNAVAILABLE explicite).
     */
    @GetMapping("/kpis")
    public ResponseEntity<Map<String, Object>> getKpis(
            @RequestParam(required = false, defaultValue = "day") String period,
            @RequestParam(required = false) String machineId) {
        return ResponseEntity.ok(dashboardService.getKpis(period, machineId));
    }

    /**
     * US52: Historique des KPIs sur une période donnée (série temporelle).
     */
    @GetMapping("/kpis/history")
    public ResponseEntity<List<Map<String, Object>>> getKpisHistory(
            @RequestParam(required = false, defaultValue = "week") String period,
            @RequestParam(required = false) String machineId,
            @RequestParam(required = false, defaultValue = "7") Integer points) {
        return ResponseEntity.ok(dashboardService.getKpisHistory(period, machineId, points));
    }

    /**
     * US53: État global de l'usine et agrégation par zone géographique.
     */
    @GetMapping("/factory-state")
    public ResponseEntity<Map<String, Object>> getFactoryState() {
        return ResponseEntity.ok(dashboardService.getFactoryState());
    }

    /**
     * US53: Détails d'une zone industrielle (métriques agrégées, machines rattachées, alertes).
     */
    @GetMapping("/zones/{zoneId}")
    public ResponseEntity<Map<String, Object>> getZoneDetails(@PathVariable String zoneId) {
        return ResponseEntity.ok(dashboardService.getZoneDetails(zoneId));
    }

    /**
     * US54: Supervision globale des anomalies industrielles (recherche, filtres, pagination, tri).
     */
    @GetMapping("/anomalies")
    public ResponseEntity<Map<String, Object>> getAnomalies(
            @RequestParam(required = false, defaultValue = "all") String period,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String machineId,
            @RequestParam(required = false) String zoneId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "timestamp,desc") String sort) {

        String[] sortParts = sort.split(",");
        Sort.Direction direction = (sortParts.length > 1 && sortParts[1].equalsIgnoreCase("asc"))
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        Sort sortObj = Sort.by(direction, sortParts[0]);

        Pageable pageable = PageRequest.of(page, Math.min(size, 100), sortObj);
        return ResponseEntity.ok(dashboardService.getAnomalies(period, severity, status, machineId, zoneId, pageable));
    }

    /**
     * US54: Statistiques globales des anomalies (répartition par sévérité, statut, capteur, top machines).
     */
    @GetMapping("/anomalies/stats")
    public ResponseEntity<Map<String, Object>> getAnomaliesStats(
            @RequestParam(required = false, defaultValue = "week") String period) {
        return ResponseEntity.ok(dashboardService.getAnomaliesStats(period));
    }

    /**
     * US55: Scores de santé de toutes les machines industrielles du parc.
     */
    @GetMapping("/health-scores")
    public ResponseEntity<Map<String, Object>> getHealthScores() {
        return ResponseEntity.ok(dashboardService.getHealthScores());
    }

    /**
     * US55: Score de santé détaillé d'une machine spécifique avec décomposition des pénalités.
     */
    @GetMapping("/health-scores/{machineId}")
    public ResponseEntity<Map<String, Object>> getMachineHealthScore(@PathVariable String machineId) {
        return ResponseEntity.ok(dashboardService.getMachineHealthScore(machineId));
    }
}
