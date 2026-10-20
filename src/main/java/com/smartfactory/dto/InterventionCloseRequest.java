package com.smartfactory.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Requête de clôture d'une intervention de maintenance (Sprint 6 - US48).
 */
public class InterventionCloseRequest {

    @NotBlank(message = "Les notes de clôture ou résumé d'intervention sont obligatoires")
    private String closureNotes;

    // Si vrai, demande le rétablissement de la machine en statut RUNNING après validation technique
    private boolean restoreMachineStatus = false;

    public InterventionCloseRequest() {
    }

    public InterventionCloseRequest(String closureNotes, boolean restoreMachineStatus) {
        this.closureNotes = closureNotes;
        this.restoreMachineStatus = restoreMachineStatus;
    }

    public String getClosureNotes() { return closureNotes; }
    public void setClosureNotes(String closureNotes) { this.closureNotes = closureNotes; }

    public boolean isRestoreMachineStatus() { return restoreMachineStatus; }
    public void setRestoreMachineStatus(boolean restoreMachineStatus) { this.restoreMachineStatus = restoreMachineStatus; }
}
