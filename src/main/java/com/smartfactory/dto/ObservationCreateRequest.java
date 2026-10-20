package com.smartfactory.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Requête d'ajout d'une observation technique (Sprint 6 - US47).
 */
public class ObservationCreateRequest {

    @NotBlank(message = "Le contenu de l'observation est obligatoire")
    private String content;

    private String category; // SAFETY, PERFORMANCE, OPERATING_CONDITION, GENERAL

    public ObservationCreateRequest() {
    }

    public ObservationCreateRequest(String content, String category) {
        this.content = content;
        this.category = category;
    }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
}
