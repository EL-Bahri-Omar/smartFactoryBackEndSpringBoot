package com.smartfactory.dto;

import jakarta.validation.constraints.NotBlank;

public class ModuleCreateRequest {

    @NotBlank(message = "Le code du module est obligatoire (ex: USER, MACHINE, MAINTENANCE)")
    private String code;

    @NotBlank(message = "Le nom du module est obligatoire")
    private String name;

    private String description;

    public ModuleCreateRequest() {
    }

    public ModuleCreateRequest(String code, String name, String description) {
        this.code = code;
        this.name = name;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
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
