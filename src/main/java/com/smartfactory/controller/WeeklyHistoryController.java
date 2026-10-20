package com.smartfactory.controller;

import com.smartfactory.service.WeeklyHistoryDatabaseService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contrôleur REST pour l'administration et l'observabilité des bases MongoDB hebdomadaires (Partie 2.2).
 */
@RestController
@RequestMapping("/api/history")
public class WeeklyHistoryController {

    private final WeeklyHistoryDatabaseService weeklyHistoryDatabaseService;

    public WeeklyHistoryController(WeeklyHistoryDatabaseService weeklyHistoryDatabaseService) {
        this.weeklyHistoryDatabaseService = weeklyHistoryDatabaseService;
    }

    /**
     * Liste toutes les bases hebdomadaires existantes et la base active courante.
     * GET /api/history/databases
     */
    @GetMapping("/databases")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'READINGS_READ', 'DASHBOARD_READ')")
    public ResponseEntity<Map<String, Object>> getWeeklyDatabases() {
        List<String> databases = weeklyHistoryDatabaseService.listExistingWeeklyDatabases();
        String currentDb = weeklyHistoryDatabaseService.getCurrentWeeklyDatabaseName();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("currentWeeklyDatabase", currentDb);
        response.put("totalWeeklyDatabases", databases.size());
        response.put("retentionMonths", weeklyHistoryDatabaseService.getRetentionMonths());
        response.put("autoCleanupEnabled", weeklyHistoryDatabaseService.isAutoCleanupEnabled());
        response.put("databases", databases);

        return ResponseEntity.ok(response);
    }

    /**
     * Simulation du nettoyage de rétention de 3 mois (dry-run).
     * GET /api/history/retention/simulate
     */
    @GetMapping("/retention/simulate")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'READINGS_READ')")
    public ResponseEntity<Map<String, Object>> simulateRetentionCleanup() {
        List<String> candidates = weeklyHistoryDatabaseService.simulateRetentionCleanup();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("mode", "SIMULATION (dry-run)");
        response.put("retentionMonths", weeklyHistoryDatabaseService.getRetentionMonths());
        response.put("expiredDatabasesCount", candidates.size());
        response.put("expiredDatabases", candidates);
        response.put("message", candidates.isEmpty()
                ? "Aucune base hebdomadaire n'a dépassé la durée de rétention de 3 mois."
                : candidates.size() + " base(s) hebdomadaire(s) expirée(s) identifiée(s) pour suppression.");

        return ResponseEntity.ok(response);
    }

    /**
     * Exécution réelle ou manuelle du nettoyage de rétention (Admin uniquement).
     * POST /api/history/retention/cleanup?dryRun=false
     */
    @PostMapping("/retention/cleanup")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<Map<String, Object>> executeRetentionCleanup(@RequestParam(defaultValue = "true") boolean dryRun) {
        List<String> affected = weeklyHistoryDatabaseService.executeRetentionCleanup(dryRun);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("mode", dryRun ? "SIMULATION" : "EXECUTION");
        response.put("count", affected.size());
        response.put("databases", affected);
        response.put("success", true);

        return ResponseEntity.ok(response);
    }
}
