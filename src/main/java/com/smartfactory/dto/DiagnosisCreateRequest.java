package com.smartfactory.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Requête d'enregistrement d'un diagnostic technique (Sprint 6 - US45).
 */
public class DiagnosisCreateRequest {

    @NotBlank(message = "La cause probable est obligatoire")
    private String probableCause;

    @NotBlank(message = "Le détail du diagnostic est obligatoire")
    private String details;

    public DiagnosisCreateRequest() {
    }

    public DiagnosisCreateRequest(String probableCause, String details) {
        this.probableCause = probableCause;
        this.details = details;
    }

    public String getProbableCause() { return probableCause; }
    public void setProbableCause(String probableCause) { this.probableCause = probableCause; }

    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }
}
