package com.smartfactory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Set;

public class RoleCreateRequest {

    @NotBlank(message = "Le nom du rôle est obligatoire")
    @Pattern(regexp = "^[A-Za-z0-9_-]{2,50}$", message = "Le nom du rôle doit comporter entre 2 et 50 caractères alphanumériques")
    private String name;

    private String description;

    private Set<String> privileges;

    public RoleCreateRequest() {
    }

    public RoleCreateRequest(String name, String description, Set<String> privileges) {
        this.name = name;
        this.description = description;
        this.privileges = privileges;
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

    public Set<String> getPrivileges() {
        return privileges;
    }

    public void setPrivileges(Set<String> privileges) {
        this.privileges = privileges;
    }
}
