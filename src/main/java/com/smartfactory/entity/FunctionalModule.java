package com.smartfactory.entity;

import jakarta.validation.constraints.NotBlank;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Entité persistée représentant un module fonctionnel dynamique de la plateforme SmartFactory (Partie 2.4).
 * Permet à l'administrateur de créer dynamiquement des modules (ex: USER, MACHINE, SENSOR, ALERT, MAINTENANCE)
 * et de configurer leurs privilèges sans recompiler le backend.
 */
@Document(collection = "functional_modules")
public class FunctionalModule {

    @Id
    private String id;

    @NotBlank(message = "Le code du module est obligatoire")
    @Indexed(unique = true)
    private String code; // ex: USER, MACHINE, SENSOR, ALERT, MAINTENANCE

    @NotBlank(message = "Le nom du module est obligatoire")
    private String name;

    private String description;

    private boolean active = true;

    private boolean systemModule = false;

    private List<ModulePrivilege> privileges = new ArrayList<>();

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public FunctionalModule() {
    }

    public FunctionalModule(String code, String name, String description, boolean systemModule) {
        this.code = code != null ? code.trim().toUpperCase() : null;
        this.name = name;
        this.description = description;
        this.systemModule = systemModule;
        this.active = true;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code != null ? code.trim().toUpperCase() : null;
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

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isSystemModule() {
        return systemModule;
    }

    public void setSystemModule(boolean systemModule) {
        this.systemModule = systemModule;
    }

    public List<ModulePrivilege> getPrivileges() {
        if (privileges == null) {
            privileges = new ArrayList<>();
        }
        return privileges;
    }

    public void setPrivileges(List<ModulePrivilege> privileges) {
        this.privileges = privileges != null ? new ArrayList<>(privileges) : new ArrayList<>();
    }

    public Optional<ModulePrivilege> findPrivilegeByCode(String privilegeCode) {
        if (privilegeCode == null || privileges == null) {
            return Optional.empty();
        }
        return privileges.stream()
                .filter(p -> privilegeCode.equalsIgnoreCase(p.getCode()))
                .findFirst();
    }

    public void addPrivilege(ModulePrivilege privilege) {
        if (privilege == null || privilege.getCode() == null) {
            return;
        }
        if (this.privileges == null) {
            this.privileges = new ArrayList<>();
        }
        this.privileges.removeIf(p -> p.getCode().equalsIgnoreCase(privilege.getCode()));
        this.privileges.add(privilege);
    }

    public boolean removePrivilege(String privilegeCode) {
        if (privilegeCode == null || this.privileges == null) {
            return false;
        }
        return this.privileges.removeIf(p -> p.getCode().equalsIgnoreCase(privilegeCode));
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
