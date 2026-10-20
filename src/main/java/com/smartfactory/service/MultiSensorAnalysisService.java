package com.smartfactory.service;

import com.smartfactory.entity.Machine;
import com.smartfactory.entity.SensorReading;
import com.smartfactory.entity.SensorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Service d'analyse multi-capteurs corrélée (Sprint 5 - US35).
 *
 * Fonctionnalités :
 * - Collecte et aligne temporellement les 4 types de mesures : Température, Vibration, Courant, Vitesse RPM.
 * - Analyse les relations et corrélations physiques entre capteurs.
 * - Détecte les combinaisons anormales industrielles :
 *   1. Surchauffe par friction / surcharge mécanique : Courant élevé + Vibration élevée + Température élevée.
 *   2. Blocage moteur / Calage : RPM nul ou très faible + Courant anormalement fort.
 *   3. Emballement / Balourd : Vitesse RPM élevée + Vibration excessive.
 *   4. Dégradation de roulement : Vibration anormale isolée sans surchauffe.
 * - Tolérance aux mesures désynchronisées ou manquantes avec diagnostic explicite.
 */
@Service
public class MultiSensorAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(MultiSensorAnalysisService.class);

    private final TelemetryCacheService telemetryCacheService;

    public MultiSensorAnalysisService(TelemetryCacheService telemetryCacheService) {
        this.telemetryCacheService = telemetryCacheService;
    }

    public static class MultiSensorAnalysisResult {
        private final String machineId;
        private final String machineCode;
        private final boolean multiSensorAnomaly;
        private final String diagnosisPattern; // MECHANICAL_OVERLOAD, MOTOR_STALL, RUNAWAY_UNBALANCE, BEARING_WEAR, NORMAL, INCOMPLETE_DATA
        private final String severity; // CRITICAL, HIGH, WARNING, NORMAL
        private final String explanation;
        private final Map<String, Object> alignedReadings;
        private final Long maxTimeDeltaSeconds;
        private final Instant timestamp;

        public MultiSensorAnalysisResult(String machineId, String machineCode, boolean multiSensorAnomaly,
                                         String diagnosisPattern, String severity, String explanation,
                                         Map<String, Object> alignedReadings, Long maxTimeDeltaSeconds) {
            this.machineId = machineId;
            this.machineCode = machineCode;
            this.multiSensorAnomaly = multiSensorAnomaly;
            this.diagnosisPattern = diagnosisPattern;
            this.severity = severity;
            this.explanation = explanation;
            this.alignedReadings = alignedReadings;
            this.maxTimeDeltaSeconds = maxTimeDeltaSeconds;
            this.timestamp = Instant.now();
        }

        public String getMachineId() { return machineId; }
        public String getMachineCode() { return machineCode; }
        public boolean isMultiSensorAnomaly() { return multiSensorAnomaly; }
        public String getDiagnosisPattern() { return diagnosisPattern; }
        public String getSeverity() { return severity; }
        public String getExplanation() { return explanation; }
        public Map<String, Object> getAlignedReadings() { return alignedReadings; }
        public Long getMaxTimeDeltaSeconds() { return maxTimeDeltaSeconds; }
        public Instant getTimestamp() { return timestamp; }
    }

    /**
     * Analyse les corrélations multi-capteurs pour une machine donnée à partir du cache temps réel.
     */
    public MultiSensorAnalysisResult analyzeMachine(Machine machine) {
        String machineId = machine.getId();
        String machineCode = machine.getCode();

        Optional<SensorReading> tempOpt = telemetryCacheService.getLatestReading(machineId, SensorType.TEMPERATURE);
        Optional<SensorReading> vibOpt = telemetryCacheService.getLatestReading(machineId, SensorType.VIBRATION);
        Optional<SensorReading> curOpt = telemetryCacheService.getLatestReading(machineId, SensorType.CURRENT);
        Optional<SensorReading> rpmOpt = telemetryCacheService.getLatestReading(machineId, SensorType.RPM);

        Map<String, Object> aligned = new LinkedHashMap<>();
        List<Instant> timestamps = new ArrayList<>();

        Double tempVal = tempOpt.map(r -> {
            timestamps.add(r.getTimestamp());
            aligned.put("temperature", Map.of("value", r.getValue(), "unit", r.getUnit(), "ts", r.getTimestamp().toString()));
            return r.getValue();
        }).orElse(null);

        Double vibVal = vibOpt.map(r -> {
            timestamps.add(r.getTimestamp());
            aligned.put("vibration", Map.of("value", r.getValue(), "unit", r.getUnit(), "ts", r.getTimestamp().toString()));
            return r.getValue();
        }).orElse(null);

        Double curVal = curOpt.map(r -> {
            timestamps.add(r.getTimestamp());
            aligned.put("current", Map.of("value", r.getValue(), "unit", r.getUnit(), "ts", r.getTimestamp().toString()));
            return r.getValue();
        }).orElse(null);

        Double rpmVal = rpmOpt.map(r -> {
            timestamps.add(r.getTimestamp());
            aligned.put("rpm", Map.of("value", r.getValue(), "unit", r.getUnit(), "ts", r.getTimestamp().toString()));
            return r.getValue();
        }).orElse(null);

        // Calcul du décalage temporel d'alignement (max delta entre les timestamps les plus anciens et récents)
        Long maxDeltaSeconds = null;
        if (timestamps.size() >= 2) {
            Instant minTs = Collections.min(timestamps);
            Instant maxTs = Collections.max(timestamps);
            maxDeltaSeconds = Duration.between(minTs, maxTs).abs().toSeconds();
        }

        // Si données manquantes
        if (tempVal == null || vibVal == null || curVal == null || rpmVal == null) {
            return new MultiSensorAnalysisResult(
                    machineId, machineCode, false, "INCOMPLETE_DATA", "NORMAL",
                    "Mesures multi-capteurs partielles (" + timestamps.size() + "/4 capteurs actifs reçus)",
                    aligned, maxDeltaSeconds
            );
        }

        // Diagnostic 1 : Calage / Blocage moteur (RPM très faible ou nul + courant élevé)
        if (rpmVal < 100.0 && curVal > 12.0) {
            String expl = String.format("Blocage mécanique / calage moteur : Vitesse quasi nulle (%.1f RPM) avec appel de courant critique (%.2f A)", rpmVal, curVal);
            log.warn("Anomalie multi-capteurs [MOTOR_STALL] sur {}: {}", machineCode, expl);
            return new MultiSensorAnalysisResult(machineId, machineCode, true, "MOTOR_STALL", "CRITICAL", expl, aligned, maxDeltaSeconds);
        }

        // Diagnostic 2 : Surcharge mécanique / friction sévère (Courant + Vibration + Température élevés)
        if (curVal > 12.0 && vibVal > 2.5 && tempVal > 60.0) {
            String expl = String.format("Surcharge mécanique et friction combinée : Courant=%.2f A, Vibration=%.2f mm/s, Température=%.1f °C", curVal, vibVal, tempVal);
            log.warn("Anomalie multi-capteurs [MECHANICAL_OVERLOAD] sur {}: {}", machineCode, expl);
            return new MultiSensorAnalysisResult(machineId, machineCode, true, "MECHANICAL_OVERLOAD", "CRITICAL", expl, aligned, maxDeltaSeconds);
        }

        // Diagnostic 3 : Emballement avec balourd (Vitesse RPM élevée + vibration excessive)
        if (rpmVal > 1500.0 && vibVal > 3.0) {
            String expl = String.format("Emballement dynamique ou déséquilibre de rotation : RPM=%.1f avec Vibration excessive=%.2f mm/s", rpmVal, vibVal);
            log.warn("Anomalie multi-capteurs [RUNAWAY_UNBALANCE] sur {}: {}", machineCode, expl);
            return new MultiSensorAnalysisResult(machineId, machineCode, true, "RUNAWAY_UNBALANCE", "HIGH", expl, aligned, maxDeltaSeconds);
        }

        // Diagnostic 4 : Dégradation de roulement (Vibration élevée avec charge et température normales)
        if (vibVal > 3.5 && curVal < 10.0 && tempVal < 65.0) {
            String expl = String.format("Dégradation de roulement ou jeu mécanique : Vibration anormale (%.2f mm/s) sous charge modérée (%.2f A)", vibVal, curVal);
            log.warn("Anomalie multi-capteurs [BEARING_WEAR] sur {}: {}", machineCode, expl);
            return new MultiSensorAnalysisResult(machineId, machineCode, true, "BEARING_WEAR", "WARNING", expl, aligned, maxDeltaSeconds);
        }

        return new MultiSensorAnalysisResult(
                machineId, machineCode, false, "NORMAL", "NORMAL",
                "Comportement multi-capteurs harmonieux sans corrélation anormale détectée",
                aligned, maxDeltaSeconds
        );
    }
}
