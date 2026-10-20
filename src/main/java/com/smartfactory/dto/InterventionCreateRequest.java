package com.smartfactory.dto;

import com.smartfactory.entity.InterventionPriority;
import com.smartfactory.entity.MaintenanceType;
import com.smartfactory.entity.PreventiveSubtype;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Requête de création d'une intervention de maintenance (Sprint 6 - US42).
 */
public class InterventionCreateRequest {

    @NotBlank(message = "L'identifiant ou code machine est obligatoire")
    private String machineId;

    @NotBlank(message = "Le titre de l'intervention est obligatoire")
    private String title;

    private String description;

    @NotNull(message = "Le type de maintenance est obligatoire (CORRECTIVE, PREVENTIVE, IMPROVEMENT)")
    private MaintenanceType type;

    // Obligatoire uniquement si type == PREVENTIVE (SYSTEMATIC ou CONDITIONAL)
    private PreventiveSubtype preventiveSubtype;

    private InterventionPriority priority = InterventionPriority.MEDIUM;

    private String alertId;

    private String anomalyId;

    private String technicianId;

    private Instant scheduledAt;

    public InterventionCreateRequest() {
    }

    public String getMachineId() { return machineId; }
    public void setMachineId(String machineId) { this.machineId = machineId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public MaintenanceType getType() { return type; }
    public void setType(MaintenanceType type) { this.type = type; }

    public PreventiveSubtype getPreventiveSubtype() { return preventiveSubtype; }
    public void setPreventiveSubtype(PreventiveSubtype preventiveSubtype) { this.preventiveSubtype = preventiveSubtype; }

    public InterventionPriority getPriority() { return priority; }
    public void setPriority(InterventionPriority priority) { this.priority = priority; }

    public String getAlertId() { return alertId; }
    public void setAlertId(String alertId) { this.alertId = alertId; }

    public String getAnomalyId() { return anomalyId; }
    public void setAnomalyId(String anomalyId) { this.anomalyId = anomalyId; }

    public String getTechnicianId() { return technicianId; }
    public void setTechnicianId(String technicianId) { this.technicianId = technicianId; }

    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; }
}
