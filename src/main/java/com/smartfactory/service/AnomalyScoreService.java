package com.smartfactory.service;

import com.smartfactory.entity.Machine;
import com.smartfactory.entity.SensorReading;
import com.smartfactory.entity.SensorType;
import com.smartfactory.repository.AlertRepository;
import com.smartfactory.repository.MachineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

/**
 * Service de calcul du score d'anomalie normalisé de 0 à 100 (Sprint 5 - US36).
 *
 * Formule documentée :
 * Score Global = min(100, Score_Seuils + Score_MultiCapteurs + Score_Alertes)
 *
 * 1. Score_Seuils (0 à 50 points) :
 *    - Capteur en statut NORMAL : 0 point
 *    - Capteur en statut WARNING : +12.5 points par capteur
 *    - Capteur en statut CRITICAL : +30 points (plafonné à 50)
 *
 * 2. Score_MultiCapteurs (0 à 25 points) :
 *    - Corrélation NORMAL : 0 point
 *    - Corrélation WARNING (ex: BEARING_WEAR) : 12 points
 *    - Corrélation HIGH (ex: RUNAWAY) : 18 points
 *    - Corrélation CRITICAL (ex: MOTOR_STALL, OVERLOAD) : 25 points
 *
 * 3. Score_Alertes (0 à 25 points) :
 *    - +10 points par alerte ACTIVE non acquittée (plafonné à 25)
 *
 * Interprétation :
 * - 0 : État machine optimal / aucun écart
 * - 1 à 25 : Risque faible / surveillance de routine
 * - 26 à 50 : Risque modéré / attention requise
 * - 51 à 75 : Risque élevé / maintenance recommandée
 * - 76 à 100 : Risque critique / intervention immédiate impérative
 *
 * Gestion des cas limites :
 * Si aucune donnée n'est disponible pour la machine, l'état 'NO_DATA' est retourné
 * avec un score null plutôt qu'une valeur arbitraire inventée.
 */
@Service
public class AnomalyScoreService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyScoreService.class);

    private final MachineRepository machineRepository;
    private final TelemetryCacheService telemetryCacheService;
    private final AlertRepository alertRepository;
    private final MultiSensorAnalysisService multiSensorAnalysisService;
    private final MachineThresholdService machineThresholdService;

    public AnomalyScoreService(MachineRepository machineRepository,
                               TelemetryCacheService telemetryCacheService,
                               AlertRepository alertRepository,
                               MultiSensorAnalysisService multiSensorAnalysisService,
                               MachineThresholdService machineThresholdService) {
        this.machineRepository = machineRepository;
        this.telemetryCacheService = telemetryCacheService;
        this.alertRepository = alertRepository;
        this.multiSensorAnalysisService = multiSensorAnalysisService;
        this.machineThresholdService = machineThresholdService;
    }

    public static class AnomalyScoreResult {
        private final String machineId;
        private final String machineCode;
        private final String state; // NORMAL, WARNING, CRITICAL, NO_DATA
        private final Double score; // 0 à 100, ou null si NO_DATA
        private final String healthCategory; // OPTIMAL, ATTENTION, HIGH_RISK, CRITICAL, UNKNOWN
        private final Map<String, Object> breakdown;
        private final String explanation;
        private final Instant calculatedAt;

        public AnomalyScoreResult(String machineId, String machineCode, String state, Double score,
                                  String healthCategory, Map<String, Object> breakdown, String explanation) {
            this.machineId = machineId;
            this.machineCode = machineCode;
            this.state = state;
            this.score = score;
            this.healthCategory = healthCategory;
            this.breakdown = breakdown;
            this.explanation = explanation;
            this.calculatedAt = Instant.now();
        }

        public String getMachineId() { return machineId; }
        public String getMachineCode() { return machineCode; }
        public String getState() { return state; }
        public Double getScore() { return score; }
        public String getHealthCategory() { return healthCategory; }
        public Map<String, Object> getBreakdown() { return breakdown; }
        public String getExplanation() { return explanation; }
        public Instant getCalculatedAt() { return calculatedAt; }
    }

    /**
     * Calcule le score d'anomalie pour une machine donnée.
     */
    public AnomalyScoreResult calculateScore(String machineIdOrCode) {
        Machine machine = machineRepository.findByCode(machineIdOrCode)
                .or(() -> machineRepository.findById(machineIdOrCode))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Machine introuvable : " + machineIdOrCode));

        String machineId = machine.getId();
        String machineCode = machine.getCode();

        // 1. Vérification de la disponibilité des données
        boolean hasAnyData = false;
        Map<String, String> sensorStatuses = new LinkedHashMap<>();
        double thresholdPoints = 0.0;

        for (SensorType type : SensorType.values()) {
            Optional<SensorReading> opt = telemetryCacheService.getLatestReading(machineId, type);
            if (opt.isPresent()) {
                hasAnyData = true;
                SensorReading r = opt.get();
                var eval = machineThresholdService.evaluateReading(machine, type, r.getValue());
                String status = eval != null && eval.getStatus() != null ? eval.getStatus() : "NORMAL";
                sensorStatuses.put(type.name(), status);

                if ("CRITICAL".equalsIgnoreCase(status)) {
                    thresholdPoints += 30.0;
                } else if ("WARNING".equalsIgnoreCase(status)) {
                    thresholdPoints += 12.5;
                }
            } else {
                sensorStatuses.put(type.name(), "NO_DATA");
            }
        }

        if (!hasAnyData) {
            return new AnomalyScoreResult(
                    machineId, machineCode, "NO_DATA", null, "UNKNOWN",
                    Collections.emptyMap(), "Aucune mesure récente disponible pour évaluer le score de cette machine"
            );
        }

        thresholdPoints = Math.min(50.0, thresholdPoints);

        // 2. Score multi-capteurs (0 à 25 points)
        var multiResult = multiSensorAnalysisService.analyzeMachine(machine);
        double multiSensorPoints = 0.0;
        if (multiResult.isMultiSensorAnomaly()) {
            switch (multiResult.getSeverity()) {
                case "CRITICAL" -> multiSensorPoints = 25.0;
                case "HIGH" -> multiSensorPoints = 18.0;
                case "WARNING" -> multiSensorPoints = 12.0;
                default -> multiSensorPoints = 5.0;
            }
        }

        // 3. Score des alertes actives non acquittées (0 à 25 points)
        long activeAlerts = alertRepository.countByMachineIdAndStatus(machineId, "ACTIVE");
        double alertPoints = Math.min(25.0, activeAlerts * 10.0);

        // 4. Somme normalisée
        double totalScore = Math.min(100.0, Math.round((thresholdPoints + multiSensorPoints + alertPoints) * 10.0) / 10.0);

        String healthCategory;
        String state;
        if (totalScore >= 75.0) {
            healthCategory = "CRITICAL";
            state = "CRITICAL";
        } else if (totalScore >= 50.0) {
            healthCategory = "HIGH_RISK";
            state = "WARNING";
        } else if (totalScore >= 25.0) {
            healthCategory = "ATTENTION";
            state = "WARNING";
        } else {
            healthCategory = "OPTIMAL";
            state = "NORMAL";
        }

        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("thresholdScore", thresholdPoints);
        breakdown.put("multiSensorScore", multiSensorPoints);
        breakdown.put("activeAlertsScore", alertPoints);
        breakdown.put("activeAlertsCount", activeAlerts);
        breakdown.put("sensorStatuses", sensorStatuses);
        breakdown.put("multiSensorPattern", multiResult.getDiagnosisPattern());

        String explanation = String.format("Score: %.1f/100 (%s). Seuils: %.1f pts, Corrélations: %.1f pts, Alertes actives: %.1f pts",
                totalScore, healthCategory, thresholdPoints, multiSensorPoints, alertPoints);

        return new AnomalyScoreResult(machineId, machineCode, state, totalScore, healthCategory, breakdown, explanation);
    }
}
