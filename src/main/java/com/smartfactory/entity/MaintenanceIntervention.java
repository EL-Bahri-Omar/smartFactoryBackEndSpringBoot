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
        @CompoundIndex(name = "techs_status_idx", def = "{'technicianIds': 1, 'status': 1}"),
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
    private List<String> technicianIds = new ArrayList<>();

    private List<String> technicianNames = new ArrayList<>();

    // Rétrocompatibilité : maintenu pour compatibilité documentaire MongoDB et clients antérieurs
    private String technicianId;

    private String technicianName;

    private String createdBy;

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

    public List<String> getTechnicianIds() {
        if (technicianIds == null) {
            technicianIds = new ArrayList<>();
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
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            this.technicianId = this.technicianIds.isEmpty() ? null : this.technicianIds.get(0);
        } else {
            this.technicianIds = new ArrayList<>();
            this.technicianId = null;
        }
    }

    public List<String> getTechnicianNames() {
        if (technicianNames == null) {
            technicianNames = new ArrayList<>();
        }
        if (technicianNames.isEmpty() && org.springframework.util.StringUtils.hasText(technicianName)) {
            technicianNames.add(technicianName.trim());
        }
        return technicianNames;
    }

    public void setTechnicianNames(List<String> technicianNames) {
        if (technicianNames != null) {
            this.technicianNames = technicianNames.stream()
                    .filter(org.springframework.util.StringUtils::hasText)
                    .map(String::trim)
                    .distinct()
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            this.technicianName = this.technicianNames.isEmpty() ? null : this.technicianNames.get(0);
        } else {
            this.technicianNames = new ArrayList<>();
            this.technicianName = null;
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
                this.technicianIds = new ArrayList<>();
            }
            String clean = technicianId.trim();
            if (!this.technicianIds.contains(clean)) {
                this.technicianIds.add(clean);
            }
        }
    }

    public String getTechnicianName() {
        if (org.springframework.util.StringUtils.hasText(technicianName)) {
            return technicianName;
        }
        return (technicianNames != null && !technicianNames.isEmpty()) ? technicianNames.get(0) : null;
    }

    public void setTechnicianName(String technicianName) {
        this.technicianName = technicianName;
        if (org.springframework.util.StringUtils.hasText(technicianName)) {
            if (this.technicianNames == null) {
                this.technicianNames = new ArrayList<>();
            }
            String clean = technicianName.trim();
            if (!this.technicianNames.contains(clean)) {
                this.technicianNames.add(clean);
            }
        }
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

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

    @org.springframework.data.annotation.Transient
    private Boolean restoreMachineStatus = true;

    public Boolean getRestoreMachineStatus() { return restoreMachineStatus; }
    public void setRestoreMachineStatus(Boolean restoreMachineStatus) { this.restoreMachineStatus = restoreMachineStatus; }
    public boolean isRestoreMachineStatus() { return restoreMachineStatus == null || restoreMachineStatus; }

    private Integer durationMinutes;

    public Integer getDurationMinutes() {
        if (durationMinutes != null && durationMinutes > 0) {
            return durationMinutes;
        }
        if (actions != null && !actions.isEmpty()) {
            int total = 0;
            for (MaintenanceAction act : actions) {
                if (act.getDurationMinutes() != null) {
                    total += act.getDurationMinutes();
                }
            }
            if (total > 0) return total;
        }
        if (startedAt != null && closedAt != null) {
            return (int) java.time.temporal.ChronoUnit.MINUTES.between(startedAt, closedAt);
        }
        return null;
    }

    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public Instant getCompletedAt() { return closedAt; }
    public void setCompletedAt(Instant completedAt) { this.closedAt = completedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
