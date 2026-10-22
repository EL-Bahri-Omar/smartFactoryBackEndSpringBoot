package com.smartfactory.dto;

import com.smartfactory.entity.InterventionPriority;
import com.smartfactory.entity.InterventionStatus;

import java.time.Instant;

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

    public String getTechnicianId() {
        return technicianId;
    }

    public void setTechnicianId(String technicianId) {
        this.technicianId = technicianId;
    }
}
