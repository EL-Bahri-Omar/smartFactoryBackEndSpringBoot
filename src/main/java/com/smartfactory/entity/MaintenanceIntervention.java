package com.smartfactory.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Entité principale représentant une intervention de maintenance industrielle (Sprint 6 - US42).
 * Persistée dans la collection 'maintenance_interventions' de la base opérationnelle
 * (non soumise à la rotation hebdomadaire des mesures simulées).
 */
@Document(collection = "maintenance_interventions")
@CompoundIndexes({
        @CompoundIndex(name = "machine_status_idx", def = "{'machineId': 1, 'status': 1}"),
        @CompoundIndex(name = "tech_status_idx", def = "{'technicianId': 1, 'status': 1}"),
        @CompoundIndex(name = "type_status_idx", def = "{'type': 1, 'status': 1}"),
        @CompoundIndex(name = "created_at_idx", def = "{'createdAt': -1}")
})
public class MaintenanceIntervention {

    @Id
    private String id;

    @Indexed
    private String machineId;

    private String machineCode;

    private String title;

    private String description;

    private MaintenanceType type; // CORRECTIVE, PREVENTIVE, IMPROVEMENT

    private PreventiveSubtype preventiveSubtype; // SYSTEMATIC, CONDITIONAL (obligatoire si PREVENTIVE, null sinon)

    private InterventionPriority priority = InterventionPriority.MEDIUM;

    private InterventionStatus status = InterventionStatus.PENDING;

    @Indexed
    private String alertId;

    @Indexed
    private String anomalyId;

    @Indexed
    private String technicianId;

    private String technicianName;

    private String assignedBy;

    private Instant assignedAt;

    private List<MaintenanceDiagnosis> diagnoses = new ArrayList<>();

    private List<MaintenanceAction> actions = new ArrayList<>();

    private List<MaintenanceObservation> observations = new ArrayList<>();

    private Instant scheduledAt;

    private Instant startedAt;

    private Instant closedAt;

    private String closedBy;

    private String closureNotes;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public MaintenanceIntervention() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public MaintenanceIntervention(String machineId, String machineCode, String title, String description,
                                   MaintenanceType type, PreventiveSubtype preventiveSubtype,
                                   InterventionPriority priority) {
        this.machineId = machineId;
        this.machineCode = machineCode;
        this.title = title;
        this.description = description;
        this.type = type;
        this.preventiveSubtype = preventiveSubtype;
        this.priority = priority != null ? priority : InterventionPriority.MEDIUM;
        this.status = InterventionStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getMachineId() { return machineId; }
    public void setMachineId(String machineId) { this.machineId = machineId; }

    public String getMachineCode() { return machineCode; }
    public void setMachineCode(String machineCode) { this.machineCode = machineCode; }

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

    public InterventionStatus getStatus() { return status; }
    public void setStatus(InterventionStatus status) { this.status = status; }

    public String getAlertId() { return alertId; }
    public void setAlertId(String alertId) { this.alertId = alertId; }

    public String getAnomalyId() { return anomalyId; }
    public void setAnomalyId(String anomalyId) { this.anomalyId = anomalyId; }

    public String getTechnicianId() { return technicianId; }
    public void setTechnicianId(String technicianId) { this.technicianId = technicianId; }

    public String getTechnicianName() { return technicianName; }
    public void setTechnicianName(String technicianName) { this.technicianName = technicianName; }

    public String getAssignedBy() { return assignedBy; }
    public void setAssignedBy(String assignedBy) { this.assignedBy = assignedBy; }

    public Instant getAssignedAt() { return assignedAt; }
    public void setAssignedAt(Instant assignedAt) { this.assignedAt = assignedAt; }

    public List<MaintenanceDiagnosis> getDiagnoses() { return diagnoses; }
    public void setDiagnoses(List<MaintenanceDiagnosis> diagnoses) { this.diagnoses = diagnoses != null ? diagnoses : new ArrayList<>(); }

    public List<MaintenanceAction> getActions() { return actions; }
    public void setActions(List<MaintenanceAction> actions) { this.actions = actions != null ? actions : new ArrayList<>(); }

    public List<MaintenanceObservation> getObservations() { return observations; }
    public void setObservations(List<MaintenanceObservation> observations) { this.observations = observations != null ? observations : new ArrayList<>(); }

    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }

    public String getClosedBy() { return closedBy; }
    public void setClosedBy(String closedBy) { this.closedBy = closedBy; }

    public String getClosureNotes() { return closureNotes; }
    public void setClosureNotes(String closureNotes) { this.closureNotes = closureNotes; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
