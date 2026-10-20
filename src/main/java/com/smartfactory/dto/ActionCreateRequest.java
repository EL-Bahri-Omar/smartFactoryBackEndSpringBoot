package com.smartfactory.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Requête d'enregistrement d'une action de maintenance (Sprint 6 - US46).
 */
public class ActionCreateRequest {

    @NotBlank(message = "La description de l'action est obligatoire")
    private String description;

    private String partsReplaced;

    private Integer durationMinutes;

    public ActionCreateRequest() {
    }

    public ActionCreateRequest(String description, String partsReplaced, Integer durationMinutes) {
        this.description = description;
        this.partsReplaced = partsReplaced;
        this.durationMinutes = durationMinutes;
    }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getPartsReplaced() { return partsReplaced; }
    public void setPartsReplaced(String partsReplaced) { this.partsReplaced = partsReplaced; }

    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }
}
