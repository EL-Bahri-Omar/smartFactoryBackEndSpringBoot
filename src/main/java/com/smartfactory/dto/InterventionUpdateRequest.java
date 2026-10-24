package com.smartfactory.dto;

import com.smartfactory.entity.InterventionPriority;
import com.smartfactory.entity.InterventionStatus;

import java.time.Instant;
import java.util.List;

/**
 * DTO de mise à jour des informations de gestion et planification d'une intervention (Section 6.2).
 * Réservé exclusivement au RESPONSABLE_INDUSTRIEL et à l'ADMIN.
 * Empêche le mass-assignment par les techniciens.
 */
public class InterventionUpdateRequest {

    private String title;

    private String description;

    private InterventionPriority priority;

    private InterventionStatus status;

    private Instant scheduledAt;

    private List<String> technicianIds;

    private String technicianId;

    public InterventionUpdateRequest() {
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public InterventionPriority getPriority() {
        return priority;
    }

    public void setPriority(InterventionPriority priority) {
        this.priority = priority;
    }

    public InterventionStatus getStatus() {
        return status;
    }

    public void setStatus(InterventionStatus status) {
        this.status = status;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(Instant scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public List<String> getTechnicianIds() {
        if (technicianIds == null && org.springframework.util.StringUtils.hasText(technicianId)) {
            technicianIds = new java.util.ArrayList<>(List.of(technicianId.trim()));
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
            this.technicianIds = null;
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
}
