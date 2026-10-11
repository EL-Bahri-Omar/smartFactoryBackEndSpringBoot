package com.smartfactory.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Locale;

/**
 * Configuration persistée des seuils de surveillance par machine et type de capteur.
 * Permet de définir des seuils d'avertissement et critiques configurables en base de données.
 */
@Document(collection = "machine_thresholds")
@CompoundIndexes({
        @CompoundIndex(name = "machine_sensor_unique_idx", def = "{'machineId': 1, 'sensorType': 1}", unique = true)
})
public class MachineThreshold {

    @Id
    private String id;

    @Indexed
    @NotBlank(message = "Machine ID is required")
    private String machineId;

    @Indexed
    private String machineCode;

    @NotNull(message = "Sensor type is required")
    private SensorType sensorType;

    private Double warningMin;
    private Double warningMax;
    private Double criticalMin;
    private Double criticalMax;

    private String unit;

    private boolean active = true;

    private String description;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public MachineThreshold() {
    }

    public MachineThreshold(String machineId, String machineCode, SensorType sensorType,
                            Double warningMin, Double warningMax,
                            Double criticalMin, Double criticalMax,
                            String unit, boolean active, String description) {
        this.machineId = machineId;
        this.machineCode = machineCode;
        this.sensorType = sensorType;
        this.warningMin = warningMin;
        this.warningMax = warningMax;
        this.criticalMin = criticalMin;
        this.criticalMax = criticalMax;
        this.unit = unit != null ? unit : (sensorType != null ? sensorType.getDefaultUnit() : null);
        this.active = active;
        this.description = description;
    }

    /**
     * Résultat de l'évaluation d'une valeur mesurée par rapport au seuil configuré.
     */
    public static class EvaluationResult {
        private final boolean alertTriggered;
        private final String severity; // WARNING, CRITICAL
        private final Double breachedThreshold;
        private final String message;

        public EvaluationResult(boolean alertTriggered, String severity, Double breachedThreshold, String message) {
            this.alertTriggered = alertTriggered;
            this.severity = severity;
            this.breachedThreshold = breachedThreshold;
            this.message = message;
        }

        public static EvaluationResult safe() {
            return new EvaluationResult(false, null, null, null);
        }

        public boolean isAlertTriggered() {
            return alertTriggered;
        }

        public String getSeverity() {
            return severity;
        }

        public Double getBreachedThreshold() {
            return breachedThreshold;
        }

        public String getMessage() {
            return message;
        }
    }

    /**
     * Évalue une valeur mesurée par rapport aux seuils configurés.
     * Priorité : le seuil critique prime sur le seuil d'avertissement.
     */
    public EvaluationResult evaluate(double value) {
        if (!active) {
            return EvaluationResult.safe();
        }

        String unitDisplay = unit != null ? unit : (sensorType != null ? sensorType.getDefaultUnit() : "");

        // 1. Dépassements critiques (priorité haute)
        if (criticalMax != null && value >= criticalMax) {
            String msg = formatAlertMessage("CRITICAL", true, value, criticalMax, unitDisplay);
            return new EvaluationResult(true, "CRITICAL", criticalMax, msg);
        }
        if (criticalMin != null && value <= criticalMin) {
            String msg = formatAlertMessage("CRITICAL", false, value, criticalMin, unitDisplay);
            return new EvaluationResult(true, "CRITICAL", criticalMin, msg);
        }

        // 2. Dépassements d'avertissement
        if (warningMax != null && value >= warningMax) {
            String msg = formatAlertMessage("WARNING", true, value, warningMax, unitDisplay);
            return new EvaluationResult(true, "WARNING", warningMax, msg);
        }
        if (warningMin != null && value <= warningMin) {
            String msg = formatAlertMessage("WARNING", false, value, warningMin, unitDisplay);
            return new EvaluationResult(true, "WARNING", warningMin, msg);
        }

        return EvaluationResult.safe();
    }

    private String formatAlertMessage(String severity, boolean isMax, double value, double threshold, String unit) {
        if (sensorType == SensorType.TEMPERATURE) {
            if ("CRITICAL".equals(severity)) {
                return String.format(Locale.ROOT, "Température critique détectée: %.1f%s (seuil: %.1f%s)", value, unit, threshold, unit);
            } else {
                return String.format(Locale.ROOT, "Avertissement température élevée: %.1f%s (seuil: %.1f%s)", value, unit, threshold, unit);
            }
        } else if (sensorType == SensorType.VIBRATION) {
            if ("CRITICAL".equals(severity)) {
                return String.format(Locale.ROOT, "Vibration critique détectée: %.2f %s (seuil: %.2f %s)", value, unit, threshold, unit);
            } else {
                return String.format(Locale.ROOT, "Avertissement vibration anormale: %.2f %s (seuil: %.2f %s)", value, unit, threshold, unit);
            }
        } else if (sensorType == SensorType.CURRENT) {
            if ("CRITICAL".equals(severity)) {
                return String.format(Locale.ROOT, "Courant critique détecté: %.2f %s (seuil: %.2f %s)", value, unit, threshold, unit);
            } else {
                return String.format(Locale.ROOT, "Avertissement surintensité: %.2f %s (seuil: %.2f %s)", value, unit, threshold, unit);
            }
        } else if (sensorType == SensorType.RPM) {
            if ("CRITICAL".equals(severity)) {
                return String.format(Locale.ROOT, "Vitesse de rotation critique: %.0f %s", value, unit);
            } else {
                return String.format(Locale.ROOT, "Avertissement vitesse de rotation anormale: %.0f %s", value, unit);
            }
        } else {
            String prefix = "CRITICAL".equals(severity) ? "Dépassement critique" : "Avertissement";
            String dir = isMax ? "seuil supérieur" : "seuil inférieur";
            return String.format(Locale.ROOT, "%s %s: %.2f %s (seuil: %.2f %s)", prefix, dir, value, unit, threshold, unit);
        }
    }

    // Getters et Setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMachineId() {
        return machineId;
    }

    public void setMachineId(String machineId) {
        this.machineId = machineId;
    }

    public String getMachineCode() {
        return machineCode;
    }

    public void setMachineCode(String machineCode) {
        this.machineCode = machineCode;
    }

    public SensorType getSensorType() {
        return sensorType;
    }

    public void setSensorType(SensorType sensorType) {
        this.sensorType = sensorType;
    }

    public Double getWarningMin() {
        return warningMin;
    }

    public void setWarningMin(Double warningMin) {
        this.warningMin = warningMin;
    }

    public Double getWarningMax() {
        return warningMax;
    }

    public void setWarningMax(Double warningMax) {
        this.warningMax = warningMax;
    }

    public Double getCriticalMin() {
        return criticalMin;
    }

    public void setCriticalMin(Double criticalMin) {
        this.criticalMin = criticalMin;
    }

    public Double getCriticalMax() {
        return criticalMax;
    }

    public void setCriticalMax(Double criticalMax) {
        this.criticalMax = criticalMax;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
