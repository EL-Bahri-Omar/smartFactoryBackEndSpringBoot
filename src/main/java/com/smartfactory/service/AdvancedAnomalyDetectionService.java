package com.smartfactory.service;

import com.smartfactory.entity.SensorReading;
import com.smartfactory.entity.SensorType;
import com.smartfactory.repository.SensorReadingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service de détection avancée d'anomalies comportementales (Sprint 5 - US34).
 *
 * Fonctionnalités :
 * - Analyse statistique multidimensionnelle sur fenêtres glissantes (Z-Score & dérive d'écart-type).
 * - Détecte les comportements inhabituels même si un seuil fixe n'est pas encore franchi.
 * - Gestion explicite des données insuffisantes : retourne 'INSUFFICIENT_DATA' si l'échantillon < 5.
 * - Résilience absolue : toute anomalie d'analyse ne bloque jamais la réception des mesures MQTT.
 */
@Service
public class AdvancedAnomalyDetectionService {

    private static final Logger log = LoggerFactory.getLogger(AdvancedAnomalyDetectionService.class);

    private static final int MIN_REQUIRED_SAMPLES = 5;
    private static final int DEFAULT_WINDOW_SIZE = 30;
    private static final double Z_SCORE_ANOMALY_THRESHOLD = 2.5;

    private final SensorReadingRepository readingRepository;

    public AdvancedAnomalyDetectionService(SensorReadingRepository readingRepository) {
        this.readingRepository = readingRepository;
    }

    /**
     * Résultat structuré de la détection avancée.
     */
    public static class DetectionResult {
        private final String status; // NORMAL, ANOMALOUS, INSUFFICIENT_DATA, ERROR
        private final boolean anomalyDetected;
        private final double anomalyScore; // 0 à 100
        private final Double zScore;
        private final Double mean;
        private final Double standardDeviation;
        private final int sampleCount;
        private final String message;

        public DetectionResult(String status, boolean anomalyDetected, double anomalyScore,
                               Double zScore, Double mean, Double standardDeviation,
                               int sampleCount, String message) {
            this.status = status;
            this.anomalyDetected = anomalyDetected;
            this.anomalyScore = anomalyScore;
            this.zScore = zScore;
            this.mean = mean;
            this.standardDeviation = standardDeviation;
            this.sampleCount = sampleCount;
            this.message = message;
        }

        public static DetectionResult insufficientData(int count) {
            return new DetectionResult("INSUFFICIENT_DATA", false, 0.0, null, null, null,
                    count, "Données historiques insuffisantes pour une détection statistique (minimum " + MIN_REQUIRED_SAMPLES + " mesures requises)");
        }

        public static DetectionResult normal(double score, double z, double mean, double std, int count) {
            return new DetectionResult("NORMAL", false, score, z, mean, std, count, "Comportement conforme aux distributions statistiques normales");
        }

        public static DetectionResult anomalous(double score, double z, double mean, double std, int count, String msg) {
            return new DetectionResult("ANOMALOUS", true, score, z, mean, std, count, msg);
        }

        public String getStatus() { return status; }
        public boolean isAnomalyDetected() { return anomalyDetected; }
        public double getAnomalyScore() { return anomalyScore; }
        public Double getZScore() { return zScore; }
        public Double getMean() { return mean; }
        public Double getStandardDeviation() { return standardDeviation; }
        public int getSampleCount() { return sampleCount; }
        public String getMessage() { return message; }
    }

    /**
     * Analyse une mesure récente par rapport à la distribution statistique historique du capteur.
     */
    public DetectionResult analyzeSensorBehavior(String machineId, SensorType sensorType, Double currentValue) {
        try {
            if (machineId == null || sensorType == null) {
                return DetectionResult.insufficientData(0);
            }

            // Récupérer les N dernières mesures de ce capteur pour la machine
            List<SensorReading> recentReadings = readingRepository.findByMachineIdAndSensorType(
                    machineId,
                    sensorType,
                    PageRequest.of(0, DEFAULT_WINDOW_SIZE, Sort.by(Sort.Direction.DESC, "timestamp"))
            ).getContent();

            if (recentReadings.size() < MIN_REQUIRED_SAMPLES) {
                return DetectionResult.insufficientData(recentReadings.size());
            }

            // Calcul de la moyenne et de l'écart-type
            double sum = 0.0;
            for (SensorReading r : recentReadings) {
                sum += r.getValue();
            }
            double mean = sum / recentReadings.size();

            double varianceSum = 0.0;
            for (SensorReading r : recentReadings) {
                varianceSum += Math.pow(r.getValue() - mean, 2);
            }
            double variance = varianceSum / recentReadings.size();
            double stdDev = Math.sqrt(variance);

            double targetVal = currentValue != null ? currentValue : recentReadings.get(0).getValue();

            // Si variance quasi nulle (capteur constant sans bruit)
            if (stdDev < 1e-4) {
                double diff = Math.abs(targetVal - mean);
                if (diff > 5.0) {
                    return DetectionResult.anomalous(80.0, 4.0, mean, stdDev, recentReadings.size(),
                            "Déviation abrupte d'un signal stationnaire : valeur=" + targetVal + " (moyenne=" + mean + ")");
                }
                return DetectionResult.normal(0.0, 0.0, mean, stdDev, recentReadings.size());
            }

            // Calcul du Z-Score : |X - µ| / σ
            double zScore = Math.abs(targetVal - mean) / stdDev;
            double anomalyScore = Math.min(100.0, Math.round((zScore / 4.0) * 100.0 * 10.0) / 10.0);

            if (zScore >= Z_SCORE_ANOMALY_THRESHOLD) {
                String msg = String.format("Déviation comportementale statistique détectée (Z-Score: %.2f > %.1f, valeur: %.2f, moyenne: %.2f, σ: %.2f)",
                        zScore, Z_SCORE_ANOMALY_THRESHOLD, targetVal, mean, stdDev);
                log.info("Détection avancée sur machine [{}] capteur [{}] : {}", machineId, sensorType, msg);
                return DetectionResult.anomalous(anomalyScore, zScore, mean, stdDev, recentReadings.size(), msg);
            }

            return DetectionResult.normal(anomalyScore, zScore, mean, stdDev, recentReadings.size());
        } catch (Exception ex) {
            log.error("Erreur non bloquante dans la détection avancée pour machine [{}]: {}", machineId, ex.getMessage());
            return new DetectionResult("ERROR", false, 0.0, null, null, null, 0, "Erreur d'analyse: " + ex.getMessage());
        }
    }
}
