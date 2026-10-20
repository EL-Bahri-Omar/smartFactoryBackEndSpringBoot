package com.smartfactory.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Requête d'assignation d'un technicien à une intervention (Sprint 6 - US43).
 */
public class InterventionAssignRequest {

    @NotBlank(message = "L'identifiant du technicien est obligatoire")
    private String technicianId;

    public InterventionAssignRequest() {
    }

    public InterventionAssignRequest(String technicianId) {
        this.technicianId = technicianId;
    }

    public String getTechnicianId() { return technicianId; }
    public void setTechnicianId(String technicianId) { this.technicianId = technicianId; }
}
