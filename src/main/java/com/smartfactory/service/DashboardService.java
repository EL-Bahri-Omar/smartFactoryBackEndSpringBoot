package com.smartfactory.service;

import com.smartfactory.entity.*;
import com.smartfactory.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
 * Service Métier - Dashboard & Supervision Industrielle (Sprint 7: US51 - US55).
 */
@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private final MachineRepository machineRepository;
    private final ZoneRepository zoneRepository;
    private final AlertRepository alertRepository;
    private final AnomalyRepository anomalyRepository;
    private final MaintenanceInterventionRepository interventionRepository;
    private final MachineThresholdRepository thresholdRepository;
    private final SensorRepository sensorRepository;
    private final SensorReadingRepository sensorReadingRepository;
    private final MongoTemplate mongoTemplate;

    public DashboardService(MachineRepository machineRepository,
                            ZoneRepository zoneRepository,
                            AlertRepository alertRepository,
                            AnomalyRepository anomalyRepository,
                            MaintenanceInterventionRepository interventionRepository,
                            MachineThresholdRepository thresholdRepository,
                            SensorRepository sensorRepository,
                            SensorReadingRepository sensorReadingRepository,
                            MongoTemplate mongoTemplate) {
        this.machineRepository = machineRepository;
        this.zoneRepository = zoneRepository;
        this.alertRepository = alertRepository;
        this.anomalyRepository = anomalyRepository;
        this.interventionRepository = interventionRepository;
        this.thresholdRepository = thresholdRepository;
        this.sensorRepository = sensorRepository;
        this.sensorReadingRepository = sensorReadingRepository;
        this.mongoTemplate = mongoTemplate;
    }

    // =========================================================================
    // US51: Vue d'ensemble du Dashboard industriel (GET /api/dashboard/overview)
    // =========================================================================

    public Map<String, Object> getOverview() {
        List<Machine> machines = machineRepository.findAll();
        long totalMachines = machines.size();

        Map<String, Long> machinesByStatus = new LinkedHashMap<>();
        for (MachineStatus status : MachineStatus.values()) {
            machinesByStatus.put(status.name(), 0L);
        }
        for (Machine m : machines) {
            MachineStatus status = m.getStatus() != null ? m.getStatus() : MachineStatus.OFFLINE;
            machinesByStatus.put(status.name(), machinesByStatus.getOrDefault(status.name(), 0L) + 1);
        }

        List<Alert> allAlerts = alertRepository.findAll();
        List<Alert> activeAlerts = allAlerts.stream()
                .filter(a -> !"RESOLVED".equalsIgnoreCase(a.getStatus()))
                .collect(Collectors.toList());

        long activeAlertsCount = activeAlerts.size();
        long criticalAlerts = activeAlerts.stream().filter(a -> "CRITICAL".equalsIgnoreCase(a.getSeverity())).count();
        long warningAlerts = activeAlerts.stream().filter(a -> "WARNING".equalsIgnoreCase(a.getSeverity())).count();
        long infoAlerts = activeAlerts.stream().filter(a -> !"CRITICAL".equalsIgnoreCase(a.getSeverity()) && !"WARNING".equalsIgnoreCase(a.getSeverity())).count();

        Map<String, Long> alertsBySeverity = new LinkedHashMap<>();
        alertsBySeverity.put("CRITICAL", criticalAlerts);
        alertsBySeverity.put("WARNING", warningAlerts);
        alertsBySeverity.put("INFO", infoAlerts);

        List<Anomaly> allAnomalies = anomalyRepository.findAll();
        long activeAnomaliesCount = allAnomalies.stream()
                .filter(a -> !"RESOLVED".equalsIgnoreCase(a.getStatus()))
                .count();

        // Overall Health Score
        double overallHealthScore = 100.0;
        if (!machines.isEmpty()) {
            double totalScore = 0.0;
            int scoredCount = 0;
            for (Machine m : machines) {
                Map<String, Object> health = computeMachineHealthScore(m);
                Number scoreNum = (Number) health.get("score");
                if (scoreNum != null) {
                    totalScore += scoreNum.doubleValue();
                    scoredCount++;
                }
            }
            if (scoredCount > 0) {
                overallHealthScore = Math.round((totalScore / scoredCount) * 10.0) / 10.0;
            }
        }

        // Factory synthesis status
        String factoryStatus;
        if (totalMachines == 0) {
            factoryStatus = "NO_DATA";
        } else if (machinesByStatus.getOrDefault("FAILURE", 0L) > 0 || criticalAlerts > 0) {
            factoryStatus = "CRITICAL";
        } else if (machinesByStatus.getOrDefault("MAINTENANCE", 0L) > 0 || machinesByStatus.getOrDefault("OFFLINE", 0L) > 0 || warningAlerts > 0) {
            factoryStatus = "WARNING";
        } else {
            factoryStatus = "OPTIMAL";
        }

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("factoryStatus", factoryStatus);
        overview.put("totalMachines", totalMachines);
        overview.put("machinesByStatus", machinesByStatus);
        overview.put("activeAlertsCount", activeAlertsCount);
        overview.put("activeAlertsBySeverity", alertsBySeverity);
        overview.put("activeAnomaliesCount", activeAnomaliesCount);
        overview.put("overallHealthScore", overallHealthScore);
        overview.put("lastUpdated", Instant.now().toString());

        return overview;
    }

    // =========================================================================
    // US52: Indicateurs clés de performance industrielle (KPIs)
    // =========================================================================

    public Map<String, Object> getKpis(String period, String machineId) {
        String normalizedPeriod = StringUtils.hasText(period) ? period.toLowerCase().trim() : "day";
        Instant now = Instant.now();
        Instant from = calculatePeriodStart(normalizedPeriod, now);

        List<Machine> targetMachines;
        if (StringUtils.hasText(machineId)) {
            Machine m = machineRepository.findById(machineId)
                    .or(() -> machineRepository.findByCode(machineId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Machine introuvable : " + machineId));
            targetMachines = List.of(m);
        } else {
            targetMachines = machineRepository.findAll();
        }

        long periodDurationMinutes = ChronoUnit.MINUTES.between(from, now);
        if (periodDurationMinutes <= 0) periodDurationMinutes = 1440; // minimum 1 jour

        long machinesCount = Math.max(1, targetMachines.size());
        long totalObservedMinutes = periodDurationMinutes * machinesCount;

        // Collecter les interventions correctives sur la période
        List<MaintenanceIntervention> correctiveInterventions = getCorrectiveInterventions(from, now, machineId);
        long failuresCount = correctiveInterventions.size();

        // Calcul du temps d'arrêt (downtime)
        long totalDowntimeMinutes = 0;
        long closedCorrectiveCount = 0;
        for (MaintenanceIntervention inter : correctiveInterventions) {
            int duration = inter.getDurationMinutes() != null && inter.getDurationMinutes() > 0 ? inter.getDurationMinutes() : 60;
            totalDowntimeMinutes += duration;
            if (inter.getStatus() == InterventionStatus.COMPLETED) {
                closedCorrectiveCount++;
            }
        }

        // Si une machine est actuellement en statut FAILURE, ajouter son indisponibilité
        for (Machine m : targetMachines) {
            if (m.getStatus() == MachineStatus.FAILURE) {
                totalDowntimeMinutes += Math.min(240, periodDurationMinutes);
                failuresCount = Math.max(failuresCount, 1);
            }
        }

        totalDowntimeMinutes = Math.min(totalObservedMinutes, totalDowntimeMinutes);
        long operatingMinutes = Math.max(0, totalObservedMinutes - totalDowntimeMinutes);

        // 1. Disponibilité %
        double availability = Math.round(((double) operatingMinutes / totalObservedMinutes) * 10000.0) / 100.0;
        availability = Math.max(0.0, Math.min(100.0, availability));

        // 2. MTBF (Mean Time Between Failures) en heures
        double operatingHours = operatingMinutes / 60.0;
        double mtbf;
        String mtbfNote;
        if (failuresCount > 0) {
            mtbf = Math.round((operatingHours / failuresCount) * 100.0) / 100.0;
            mtbfNote = "Calculé sur " + failuresCount + " panne(s) observée(s)";
        } else {
            mtbf = Math.round(operatingHours * 100.0) / 100.0;
            mtbfNote = "Aucune panne constatée sur la période observée";
        }

        // 3. MTTR (Mean Time To Repair) en minutes et heures
        double mttrMinutes = 0.0;
        double mttrHours = 0.0;
        if (closedCorrectiveCount > 0) {
            mttrMinutes = Math.round(((double) totalDowntimeMinutes / closedCorrectiveCount) * 10.0) / 10.0;
            mttrHours = Math.round((mttrMinutes / 60.0) * 100.0) / 100.0;
        }

        // 4. TRS / OEE (Overall Equipment Effectiveness)
        // RÈGLE MÉTIER STRICTE : Le simulateur ne remonte pas la qualité/cadence. L'OEE doit être déclaré UNAVAILABLE.
        Map<String, Object> oee = new LinkedHashMap<>();
        oee.put("status", "UNAVAILABLE");
        oee.put("value", null);
        oee.put("reason", "Les données de production requises pour le calcul du TRS (quantité de pièces conformes/rebutées, cadence nominale et temps de cycle théorique) ne sont pas instrumentées par les capteurs existants (qui ne fournissent que TEMPERATURE, VIBRATION, CURRENT, RPM).");
        oee.put("missingMetrics", List.of(
                "goodPartsCount (Nombre de pièces bonnes conformes)",
                "totalPartsProduced (Nombre total de pièces produites)",
                "idealCycleTime (Temps de cycle théorique / cadence nominale)"
        ));

        Map<String, Object> oeeComponents = new LinkedHashMap<>();
        oeeComponents.put("availability", Map.of(
                "status", "AVAILABLE",
                "value", availability,
                "unit", "%"
        ));
        oeeComponents.put("performance", Map.of(
                "status", "UNAVAILABLE",
                "reason", "Cadence nominale et vitesse d'exécution non instrumentées"
        ));
        oeeComponents.put("quality", Map.of(
                "status", "UNAVAILABLE",
                "reason", "Comptage des pièces bonnes et rebuts non instrumenté"
        ));
        oee.put("components", oeeComponents);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("period", normalizedPeriod);
        response.put("machineId", machineId);
        response.put("totalMachinesObserved", targetMachines.size());
        response.put("availability", Map.of(
                "value", availability,
                "unit", "%",
                "operatingMinutes", operatingMinutes,
                "downtimeMinutes", totalDowntimeMinutes,
                "totalObservedMinutes", totalObservedMinutes
        ));
        response.put("mtbf", Map.of(
                "valueHours", mtbf,
                "unit", "hours",
                "failureCount", failuresCount,
                "note", mtbfNote
        ));
        response.put("mttr", Map.of(
                "valueMinutes", mttrMinutes,
                "valueHours", mttrHours,
                "closedInterventionsCount", closedCorrectiveCount
        ));
        response.put("oee", oee);
        response.put("calculatedAt", now.toString());

        return response;
    }

    public List<Map<String, Object>> getKpisHistory(String period, String machineId, Integer points) {
        String normalizedPeriod = StringUtils.hasText(period) ? period.toLowerCase().trim() : "week";
        int numPoints = (points != null && points > 0 && points <= 30) ? points : 7;

        Instant now = Instant.now();
        Instant from = calculatePeriodStart(normalizedPeriod, now);

        long totalSeconds = ChronoUnit.SECONDS.between(from, now);
        long stepSeconds = Math.max(60, totalSeconds / numPoints);

        List<Map<String, Object>> history = new ArrayList<>();
        for (int i = 0; i < numPoints; i++) {
            Instant bucketStart = from.plus(i * stepSeconds, ChronoUnit.SECONDS);
            Instant bucketEnd = (i == numPoints - 1) ? now : bucketStart.plus(stepSeconds, ChronoUnit.SECONDS);

            // Calcul simplifié sur le sous-intervalle
            List<MaintenanceIntervention> bucketInterventions = getCorrectiveInterventions(bucketStart, bucketEnd, machineId);
            long downtime = 0;
            for (MaintenanceIntervention inter : bucketInterventions) {
                downtime += (inter.getDurationMinutes() != null && inter.getDurationMinutes() > 0) ? inter.getDurationMinutes() : 30;
            }
            long bucketMinutes = Math.max(1, ChronoUnit.MINUTES.between(bucketStart, bucketEnd));
            double avail = Math.max(0.0, Math.min(100.0, Math.round(((double) Math.max(0, bucketMinutes - downtime) / bucketMinutes) * 10000.0) / 100.0));

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("timestamp", bucketStart.toString());
            point.put("availability", avail);
            point.put("failuresCount", bucketInterventions.size());
            point.put("downtimeMinutes", downtime);
            history.add(point);
        }

        return history;
    }

    // =========================================================================
    // US53: État de l'usine & Agrégation par Zone
    // =========================================================================

    public Map<String, Object> getFactoryState() {
        List<Zone> zones = zoneRepository.findAll();
        List<Machine> allMachines = machineRepository.findAll();
        List<Alert> allAlerts = alertRepository.findAll();

        List<Alert> activeAlerts = allAlerts.stream()
                .filter(a -> !"RESOLVED".equalsIgnoreCase(a.getStatus()))
                .collect(Collectors.toList());

        Map<String, List<Machine>> machinesByZone = allMachines.stream()
                .filter(m -> StringUtils.hasText(m.getZoneId()))
                .collect(Collectors.groupingBy(Machine::getZoneId));

        List<Map<String, Object>> zoneSummaries = new ArrayList<>();
        long totalCritical = 0;
        long totalWarning = 0;

        for (Zone zone : zones) {
            List<Machine> zoneMachines = machinesByZone.getOrDefault(zone.getId(), Collections.emptyList());
            Map<String, Long> statusCounts = new LinkedHashMap<>();
            for (MachineStatus s : MachineStatus.values()) {
                statusCounts.put(s.name(), 0L);
            }
            Set<String> zoneMachineIds = new HashSet<>();
            for (Machine zm : zoneMachines) {
                zoneMachineIds.add(zm.getId());
                MachineStatus s = zm.getStatus() != null ? zm.getStatus() : MachineStatus.OFFLINE;
                statusCounts.put(s.name(), statusCounts.getOrDefault(s.name(), 0L) + 1);
            }

            List<Alert> zoneAlerts = activeAlerts.stream()
                    .filter(a -> zoneMachineIds.contains(a.getMachineId()))
                    .collect(Collectors.toList());

            long crit = zoneAlerts.stream().filter(a -> "CRITICAL".equalsIgnoreCase(a.getSeverity())).count();
            long warn = zoneAlerts.stream().filter(a -> "WARNING".equalsIgnoreCase(a.getSeverity())).count();
            totalCritical += crit;
            totalWarning += warn;

            String zoneStatus;
            if (statusCounts.getOrDefault("FAILURE", 0L) > 0 || crit > 0) {
                zoneStatus = "CRITICAL";
            } else if (statusCounts.getOrDefault("MAINTENANCE", 0L) > 0 || statusCounts.getOrDefault("OFFLINE", 0L) > 0 || warn > 0) {
                zoneStatus = "WARNING";
            } else {
                zoneStatus = "NORMAL";
            }

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("zoneId", zone.getId());
            summary.put("zoneName", zone.getName());
            summary.put("description", zone.getDescription());
            summary.put("location", zone.getLocation());
            summary.put("totalMachines", zoneMachines.size());
            summary.put("machinesByStatus", statusCounts);
            summary.put("activeAlertsCount", zoneAlerts.size());
            summary.put("criticalAlertsCount", crit);
            summary.put("warningAlertsCount", warn);
            summary.put("zoneStatus", zoneStatus);

            zoneSummaries.add(summary);
        }

        String factoryStatus;
        if (allMachines.isEmpty()) {
            factoryStatus = "NO_DATA";
        } else if (totalCritical > 0 || allMachines.stream().anyMatch(m -> m.getStatus() == MachineStatus.FAILURE)) {
            factoryStatus = "CRITICAL";
        } else if (totalWarning > 0 || allMachines.stream().anyMatch(m -> m.getStatus() == MachineStatus.MAINTENANCE || m.getStatus() == MachineStatus.OFFLINE)) {
            factoryStatus = "WARNING";
        } else {
            factoryStatus = "OPTIMAL";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("factoryStatus", factoryStatus);
        result.put("totalZones", zones.size());
        result.put("totalMachines", allMachines.size());
        result.put("activeAlertsCount", activeAlerts.size());
        result.put("zones", zoneSummaries);
        result.put("timestamp", Instant.now().toString());

        return result;
    }

    public Map<String, Object> getZoneDetails(String zoneId) {
        Zone zone = zoneRepository.findById(zoneId)
                .or(() -> zoneRepository.findByName(zoneId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Zone introuvable avec l'identifiant : " + zoneId));

        List<Machine> machines = machineRepository.findByZoneId(zone.getId());
        List<Map<String, Object>> machineDetails = new ArrayList<>();

        Map<String, Long> statusCounts = new LinkedHashMap<>();
        for (MachineStatus s : MachineStatus.values()) {
            statusCounts.put(s.name(), 0L);
        }

        long activeAlertsInZone = 0;
        for (Machine m : machines) {
            MachineStatus status = m.getStatus() != null ? m.getStatus() : MachineStatus.OFFLINE;
            statusCounts.put(status.name(), statusCounts.getOrDefault(status.name(), 0L) + 1);

            Map<String, Object> health = computeMachineHealthScore(m);
            List<Alert> alerts = alertRepository.findByMachineIdAndStatus(m.getId(), "ACTIVE");
            activeAlertsInZone += alerts.size();

            Map<String, Object> mInfo = new LinkedHashMap<>();
            mInfo.put("id", m.getId());
            mInfo.put("code", m.getCode());
            mInfo.put("name", m.getName());
            mInfo.put("type", m.getType());
            mInfo.put("status", status.name());
            mInfo.put("healthScore", health.get("score"));
            mInfo.put("healthGrade", health.get("grade"));
            mInfo.put("activeAlertsCount", alerts.size());

            machineDetails.add(mInfo);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("zoneId", zone.getId());
        result.put("zoneName", zone.getName());
        result.put("description", zone.getDescription());
        result.put("location", zone.getLocation());
        result.put("totalMachines", machines.size());
        result.put("machinesByStatus", statusCounts);
        result.put("activeAlertsCount", activeAlertsInZone);
        result.put("machines", machineDetails);
        result.put("timestamp", Instant.now().toString());

        return result;
    }

    // =========================================================================
    // US54: Supervision globale des Anomalies (GET /api/dashboard/anomalies)
    // =========================================================================

    public Map<String, Object> getAnomalies(String period, String severity, String status, String machineId, String zoneId, Pageable pageable) {
        Criteria criteria = new Criteria();
        List<Criteria> subCriteria = new ArrayList<>();

        if (StringUtils.hasText(period) && !"all".equalsIgnoreCase(period)) {
            Instant from = calculatePeriodStart(period, Instant.now());
            subCriteria.add(Criteria.where("timestamp").gte(from));
        }

        if (StringUtils.hasText(severity)) {
            subCriteria.add(Criteria.where("severity").is(severity.toUpperCase().trim()));
        }

        if (StringUtils.hasText(status)) {
            subCriteria.add(Criteria.where("status").is(status.toUpperCase().trim()));
        }

        if (StringUtils.hasText(machineId)) {
            subCriteria.add(Criteria.where("machineId").is(machineId));
        } else if (StringUtils.hasText(zoneId)) {
            List<Machine> zoneMachines = machineRepository.findByZoneId(zoneId);
            List<String> machineIds = zoneMachines.stream().map(Machine::getId).collect(Collectors.toList());
            subCriteria.add(Criteria.where("machineId").in(machineIds));
        }

        if (!subCriteria.isEmpty()) {
            criteria.andOperator(subCriteria.toArray(new Criteria[0]));
        }

        Query query = new Query(criteria);
        long totalElements = mongoTemplate.count(query, Anomaly.class);

        if (pageable != null) {
            query.with(pageable);
        } else {
            query.with(Sort.by(Sort.Direction.DESC, "timestamp"));
        }

        List<Anomaly> anomalies = mongoTemplate.find(query, Anomaly.class);

        // Enrichir avec les informations machines
        Map<String, Machine> machineCache = new HashMap<>();
        List<Map<String, Object>> content = new ArrayList<>();
        for (Anomaly a : anomalies) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", a.getId());
            item.put("machineId", a.getMachineId());
            item.put("machineCode", a.getMachineCode());
            item.put("sensorId", a.getSensorId());
            item.put("sensorType", a.getSensorType() != null ? a.getSensorType().name() : null);
            item.put("observedValue", a.getObservedValue());
            item.put("thresholdValue", a.getThresholdValue());
            item.put("thresholdType", a.getThresholdType());
            item.put("severity", a.getSeverity());
            item.put("status", a.getStatus());
            item.put("occurrenceCount", a.getOccurrenceCount());
            item.put("message", a.getMessage());
            item.put("alertId", a.getAlertId());
            item.put("timestamp", a.getTimestamp() != null ? a.getTimestamp().toString() : null);

            if (StringUtils.hasText(a.getMachineId())) {
                Machine m = machineCache.computeIfAbsent(a.getMachineId(), id -> machineRepository.findById(id).orElse(null));
                if (m != null) {
                    item.put("machineName", m.getName());
                    item.put("zoneId", m.getZoneId());
                }
            }
            content.add(item);
        }

        int pageSize = pageable != null ? pageable.getPageSize() : 20;
        int pageNumber = pageable != null ? pageable.getPageNumber() : 0;
        int totalPages = pageSize > 0 ? (int) Math.ceil((double) totalElements / pageSize) : 1;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("totalElements", totalElements);
        result.put("page", pageNumber);
        result.put("size", pageSize);
        result.put("totalPages", totalPages);

        return result;
    }

    public Map<String, Object> getAnomaliesStats(String period) {
        String normalizedPeriod = StringUtils.hasText(period) ? period.toLowerCase().trim() : "week";
        Instant from = calculatePeriodStart(normalizedPeriod, Instant.now());

        Query query = new Query(Criteria.where("timestamp").gte(from));
        List<Anomaly> anomalies = mongoTemplate.find(query, Anomaly.class);

        long total = anomalies.size();

        Map<String, Long> bySeverity = new LinkedHashMap<>();
        bySeverity.put("CRITICAL", anomalies.stream().filter(a -> "CRITICAL".equalsIgnoreCase(a.getSeverity())).count());
        bySeverity.put("WARNING", anomalies.stream().filter(a -> "WARNING".equalsIgnoreCase(a.getSeverity())).count());
        bySeverity.put("INFO", anomalies.stream().filter(a -> "INFO".equalsIgnoreCase(a.getSeverity())).count());

        Map<String, Long> byStatus = new LinkedHashMap<>();
        byStatus.put("OPEN", anomalies.stream().filter(a -> "OPEN".equalsIgnoreCase(a.getStatus()) || "DETECTED".equalsIgnoreCase(a.getStatus())).count());
        byStatus.put("ACKNOWLEDGED", anomalies.stream().filter(a -> "ACKNOWLEDGED".equalsIgnoreCase(a.getStatus()) || "IN_PROGRESS".equalsIgnoreCase(a.getStatus())).count());
        byStatus.put("RESOLVED", anomalies.stream().filter(a -> "RESOLVED".equalsIgnoreCase(a.getStatus())).count());

        Map<String, Long> bySensorType = new LinkedHashMap<>();
        for (SensorType st : SensorType.values()) {
            bySensorType.put(st.name(), anomalies.stream().filter(a -> a.getSensorType() == st).count());
        }

        // Top machines avec le plus d'anomalies
        Map<String, Long> countByMachine = anomalies.stream()
                .filter(a -> StringUtils.hasText(a.getMachineCode()) || StringUtils.hasText(a.getMachineId()))
                .collect(Collectors.groupingBy(
                        a -> StringUtils.hasText(a.getMachineCode()) ? a.getMachineCode() : a.getMachineId(),
                        Collectors.counting()
                ));

        List<Map<String, Object>> topMachines = countByMachine.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("machineCode", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());

        long resolvedCount = byStatus.getOrDefault("RESOLVED", 0L);
        double resolutionRate = total > 0 ? Math.round(((double) resolvedCount / total) * 1000.0) / 10.0 : 100.0;

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("period", normalizedPeriod);
        stats.put("totalAnomalies", total);
        stats.put("bySeverity", bySeverity);
        stats.put("byStatus", byStatus);
        stats.put("bySensorType", bySensorType);
        stats.put("topMachines", topMachines);
        stats.put("resolutionRate", resolutionRate);
        stats.put("generatedAt", Instant.now().toString());

        return stats;
    }

    // =========================================================================
    // US55: Score de santé machine (Health Score 0-100)
    // =========================================================================

    public Map<String, Object> getHealthScores() {
        List<Machine> machines = machineRepository.findAll();

        int excellent = 0;
        int good = 0;
        int warning = 0;
        int critical = 0;
        int insufficientData = 0;

        double totalScore = 0.0;
        int validScoreCount = 0;

        List<Map<String, Object>> list = new ArrayList<>();
        for (Machine m : machines) {
            Map<String, Object> health = computeMachineHealthScore(m);
            list.add(health);

            String grade = (String) health.get("grade");
            if ("EXCELLENT".equalsIgnoreCase(grade)) excellent++;
            else if ("GOOD".equalsIgnoreCase(grade)) good++;
            else if ("WARNING".equalsIgnoreCase(grade)) warning++;
            else if ("CRITICAL".equalsIgnoreCase(grade)) critical++;
            else if ("INSUFFICIENT_DATA".equalsIgnoreCase(grade)) insufficientData++;

            Number score = (Number) health.get("score");
            if (score != null) {
                totalScore += score.doubleValue();
                validScoreCount++;
            }
        }

        double averageScore = validScoreCount > 0 ? Math.round((totalScore / validScoreCount) * 10.0) / 10.0 : 100.0;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalMachines", machines.size());
        response.put("averageScore", averageScore);
        response.put("distribution", Map.of(
                "excellent", excellent,
                "good", good,
                "warning", warning,
                "critical", critical,
                "insufficientData", insufficientData
        ));
        response.put("machines", list);
        response.put("calculatedAt", Instant.now().toString());

        return response;
    }

    public Map<String, Object> getMachineHealthScore(String machineId) {
        Machine machine = machineRepository.findById(machineId)
                .or(() -> machineRepository.findByCode(machineId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Machine introuvable : " + machineId));

        return computeMachineHealthScore(machine);
    }

    /**
     * Algorithme déterministe de calcul du Health Score d'une machine (0 - 100).
     */
    public Map<String, Object> computeMachineHealthScore(Machine machine) {
        String machineId = machine.getId();

        // 1. Vérification si la machine dispose de données/capteurs
        List<Sensor> sensors = sensorRepository.findByMachineId(machineId);
        List<Alert> allAlerts = alertRepository.findByMachineId(machineId);
        boolean hasTelemetry = !sensors.isEmpty() || !allAlerts.isEmpty();

        if (!hasTelemetry && machine.getStatus() == MachineStatus.OFFLINE) {
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("machineId", machineId);
            res.put("machineCode", machine.getCode());
            res.put("machineName", machine.getName());
            res.put("zoneId", machine.getZoneId());
            res.put("status", "INSUFFICIENT_DATA");
            res.put("score", null);
            res.put("grade", "INSUFFICIENT_DATA");
            res.put("message", "Données insuffisantes : aucun capteur ou télémétrie disponible pour cette machine");
            res.put("penalties", Collections.emptyMap());
            res.put("recommendations", List.of("Configurer et raccorder les capteurs IoT de la machine"));
            return res;
        }

        int baseScore = 100;
        int statusPenalty = 0;
        int alertsPenalty = 0;
        int anomaliesPenalty = 0;
        int sensorsPenalty = 0;

        List<String> penaltyDetails = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();

        // 2. Pénalité liée au statut de la machine
        MachineStatus status = machine.getStatus() != null ? machine.getStatus() : MachineStatus.OFFLINE;
        switch (status) {
            case FAILURE -> {
                statusPenalty = 40;
                penaltyDetails.add("Machine en panne déclarée (-40 pts)");
                recommendations.add("Intervention corrective immédiate requise");
            }
            case MAINTENANCE -> {
                statusPenalty = 20;
                penaltyDetails.add("Machine en cours de maintenance (-20 pts)");
                recommendations.add("Suivre la progression de l'intervention de maintenance");
            }
            case OFFLINE -> {
                statusPenalty = 15;
                penaltyDetails.add("Machine hors ligne (-15 pts)");
                recommendations.add("Vérifier la connectivité réseau et l'alimentation de la machine");
            }
            case IDLE -> {
                statusPenalty = 5;
                penaltyDetails.add("Machine à l'arrêt/en veille (-5 pts)");
            }
            case RUNNING -> {
                statusPenalty = 0;
            }
        }

        // 3. Pénalité liée aux alertes actives
        List<Alert> activeAlerts = alertRepository.findByMachineIdAndStatus(machineId, "ACTIVE");
        long critAlerts = activeAlerts.stream().filter(a -> "CRITICAL".equalsIgnoreCase(a.getSeverity())).count();
        long warnAlerts = activeAlerts.stream().filter(a -> "WARNING".equalsIgnoreCase(a.getSeverity())).count();

        int critPenalty = (int) Math.min(50, critAlerts * 25);
        int warnPenalty = (int) Math.min(30, warnAlerts * 10);
        alertsPenalty = critPenalty + warnPenalty;

        if (critAlerts > 0) {
            penaltyDetails.add(critAlerts + " alerte(s) critique(s) active(s) (-" + critPenalty + " pts)");
            recommendations.add("Acquitter et traiter en priorité les alertes critiques");
        }
        if (warnAlerts > 0) {
            penaltyDetails.add(warnAlerts + " alerte(s) d'avertissement active(s) (-" + warnPenalty + " pts)");
        }

        // 4. Pénalité liée aux anomalies non résolues
        List<Anomaly> activeAnomalies = anomalyRepository.findByMachineIdAndStatus(machineId, "OPEN");
        long critAnomalies = activeAnomalies.stream().filter(a -> "CRITICAL".equalsIgnoreCase(a.getSeverity())).count();
        long warnAnomalies = activeAnomalies.stream().filter(a -> "WARNING".equalsIgnoreCase(a.getSeverity())).count();

        anomaliesPenalty = (int) Math.min(25, (critAnomalies * 10) + (warnAnomalies * 5));
        if (anomaliesPenalty > 0) {
            penaltyDetails.add(activeAnomalies.size() + " anomalie(s) non résolue(s) (-" + anomaliesPenalty + " pts)");
        }

        // 5. Pénalité liée aux dépassements de seuils des capteurs
        List<MachineThreshold> thresholds = thresholdRepository.findByMachineId(machineId);
        for (MachineThreshold th : thresholds) {
            SensorType st = th.getSensorType();
            List<SensorReading> latestReadings = sensorReadingRepository.findByMachineIdAndSensorType(
                    machineId, st, Sort.by(Sort.Direction.DESC, "timestamp")
            );
            if (!latestReadings.isEmpty()) {
                double val = latestReadings.get(0).getValue();
                if ((th.getCriticalMax() != null && val >= th.getCriticalMax()) ||
                    (th.getCriticalMin() != null && val <= th.getCriticalMin())) {
                    sensorsPenalty = Math.min(30, sensorsPenalty + 15);
                    penaltyDetails.add("Valeur " + st.name() + " (" + val + ") en zone critique");
                    if (st == SensorType.TEMPERATURE) recommendations.add("Vérifier le circuit de refroidissement");
                    if (st == SensorType.VIBRATION) recommendations.add("Contrôler le serrage et l'état des paliers/roulements");
                    if (st == SensorType.CURRENT) recommendations.add("Vérifier l'alimentation électrique et la charge");
                } else if ((th.getWarningMax() != null && val >= th.getWarningMax()) ||
                           (th.getWarningMin() != null && val <= th.getWarningMin())) {
                    sensorsPenalty = Math.min(30, sensorsPenalty + 5);
                    penaltyDetails.add("Valeur " + st.name() + " (" + val + ") en zone d'alerte");
                }
            }
        }

        int totalPenalties = statusPenalty + alertsPenalty + anomaliesPenalty + sensorsPenalty;
        int finalScore = Math.max(0, Math.min(100, baseScore - totalPenalties));

        String grade;
        if (finalScore >= 80) {
            grade = "EXCELLENT";
            if (recommendations.isEmpty()) {
                recommendations.add("Machine en parfait état de fonctionnement");
            }
        } else if (finalScore >= 60) {
            grade = "GOOD";
            if (recommendations.isEmpty()) {
                recommendations.add("Fonctionnement nominal, surveillance standard recommandée");
            }
        } else if (finalScore >= 40) {
            grade = "WARNING";
            recommendations.add("Planifier une inspection préventive ciblée");
        } else {
            grade = "CRITICAL";
            recommendations.add("Action corrective immédiate requise pour éviter un arrêt prolongé");
        }

        Map<String, Object> penalties = new LinkedHashMap<>();
        penalties.put("machineStatus", statusPenalty);
        penalties.put("activeAlerts", alertsPenalty);
        penalties.put("unresolvedAnomalies", anomaliesPenalty);
        penalties.put("sensorThresholds", sensorsPenalty);
        penalties.put("totalPenalties", totalPenalties);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("machineId", machineId);
        res.put("machineCode", machine.getCode());
        res.put("machineName", machine.getName());
        res.put("zoneId", machine.getZoneId());
        res.put("status", status.name());
        res.put("score", finalScore);
        res.put("grade", grade);
        res.put("penalties", penalties);
        res.put("penaltyDetails", penaltyDetails);
        res.put("recommendations", recommendations);
        res.put("calculatedAt", Instant.now().toString());

        return res;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Instant calculatePeriodStart(String period, Instant now) {
        if ("month".equalsIgnoreCase(period)) {
            return now.minus(30, ChronoUnit.DAYS);
        } else if ("week".equalsIgnoreCase(period)) {
            return now.minus(7, ChronoUnit.DAYS);
        } else { // default: "day"
            return now.minus(24, ChronoUnit.HOURS);
        }
    }

    private List<MaintenanceIntervention> getCorrectiveInterventions(Instant from, Instant to, String machineId) {
        List<MaintenanceIntervention> interventions;
        if (StringUtils.hasText(machineId)) {
            interventions = interventionRepository.findByMachineIdAndCreatedAtBetween(machineId, from, to);
        } else {
            interventions = interventionRepository.findByCreatedAtBetween(from, to);
        }
        return interventions.stream()
                .filter(i -> i.getType() == MaintenanceType.CORRECTIVE)
                .collect(Collectors.toList());
    }
}
