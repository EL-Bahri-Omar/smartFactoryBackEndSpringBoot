package com.smartfactory.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * Action réalisée lors d'une intervention de maintenance (Sprint 6 - US46).
 */
public class MaintenanceAction {

    private String id;
    private String description;
    private String technicianId;
    private String technicianName;
    private String partsReplaced;
    private Integer durationMinutes;
    private Instant performedAt;

    public MaintenanceAction() {
        this.id = UUID.randomUUID().toString();
        this.performedAt = Instant.now();
    }

    public MaintenanceAction(String description, String technicianId, String technicianName, String partsReplaced, Integer durationMinutes) {
        this.id = UUID.randomUUID().toString();
        this.description = description;
        this.technicianId = technicianId;
        this.technicianName = technicianName;
        this.partsReplaced = partsReplaced;
        this.durationMinutes = durationMinutes;
        this.performedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getTechnicianId() { return technicianId; }
    public void setTechnicianId(String technicianId) { this.technicianId = technicianId; }

    public String getTechnicianName() { return technicianName; }
    public void setTechnicianName(String technicianName) { this.technicianName = technicianName; }

    public String getPartsReplaced() { return partsReplaced; }
    public void setPartsReplaced(String partsReplaced) { this.partsReplaced = partsReplaced; }

    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }

    public Instant getPerformedAt() { return performedAt; }
    public void setPerformedAt(Instant performedAt) { this.performedAt = performedAt; }
}
