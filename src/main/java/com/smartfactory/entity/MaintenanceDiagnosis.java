package com.smartfactory.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * Diagnostic technique enregistré lors d'une intervention (Sprint 6 - US45).
 */
public class MaintenanceDiagnosis {

    private String id;
    private String probableCause;
    private String details;
    private String technicianId;
    private String technicianName;
    private Instant recordedAt;
    private Instant updatedAt;

    public MaintenanceDiagnosis() {
        this.id = UUID.randomUUID().toString();
        this.recordedAt = Instant.now();
        this.updatedAt = this.recordedAt;
    }

    public MaintenanceDiagnosis(String probableCause, String details, String technicianId, String technicianName) {
        this.id = UUID.randomUUID().toString();
        this.probableCause = probableCause;
        this.details = details;
        this.technicianId = technicianId;
        this.technicianName = technicianName;
        this.recordedAt = Instant.now();
        this.updatedAt = this.recordedAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProbableCause() { return probableCause; }
    public void setProbableCause(String probableCause) { this.probableCause = probableCause; }

    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }

    public String getTechnicianId() { return technicianId; }
    public void setTechnicianId(String technicianId) { this.technicianId = technicianId; }

    public String getTechnicianName() { return technicianName; }
    public void setTechnicianName(String technicianName) { this.technicianName = technicianName; }

    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
