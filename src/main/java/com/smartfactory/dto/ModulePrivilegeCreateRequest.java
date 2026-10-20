package com.smartfactory.dto;

import jakarta.validation.constraints.NotBlank;

public class ModulePrivilegeCreateRequest {

    @NotBlank(message = "L'action du privilège est obligatoire (ex: CONSULTER, AJOUTER, MODIFIER, SUPPRIMER)")
    private String action;

    private String name;

    private String description;

    public ModulePrivilegeCreateRequest() {
    }

    public ModulePrivilegeCreateRequest(String action, String name, String description) {
        this.action = action;
        this.name = name;
        this.description = description;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
