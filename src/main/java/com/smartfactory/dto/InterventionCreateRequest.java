package com.smartfactory.dto;

import com.smartfactory.entity.InterventionPriority;
import com.smartfactory.entity.MaintenanceType;
import com.smartfactory.entity.PreventiveSubtype;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

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

    private List<String> technicianIds = new java.util.ArrayList<>();

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

    public List<String> getTechnicianIds() {
        if (technicianIds == null) {
            technicianIds = new java.util.ArrayList<>();
        }
        if (technicianIds.isEmpty() && org.springframework.util.StringUtils.hasText(technicianId)) {
            technicianIds.add(technicianId.trim());
        }
        return technicianIds;
    }

    public void setTechnicianIds(List<String> technicianIds) {
        if (technicianIds != null) {
            this.technicianIds = technicianIds.stream()
                    .filter(org.springframework.util.StringUtils::hasText)
                    .map(String::trim)
                    .distinct()
                    .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
            this.technicianId = this.technicianIds.isEmpty() ? null : this.technicianIds.get(0);
        } else {
            this.technicianIds = new java.util.ArrayList<>();
            this.technicianId = null;
        }
    }

    public String getTechnicianId() {
        if (org.springframework.util.StringUtils.hasText(technicianId)) {
            return technicianId;
        }
        return (technicianIds != null && !technicianIds.isEmpty()) ? technicianIds.get(0) : null;
    }

    public void setTechnicianId(String technicianId) {
        this.technicianId = technicianId;
        if (org.springframework.util.StringUtils.hasText(technicianId)) {
            if (this.technicianIds == null) {
                this.technicianIds = new java.util.ArrayList<>();
            }
            String clean = technicianId.trim();
            if (!this.technicianIds.contains(clean)) {
                this.technicianIds.add(clean);
            }
        }
    }

    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; }
}
