package com.smartfactory.dto;

import com.smartfactory.entity.ModulePrivilege;

import java.time.Instant;
import java.util.List;

public class ModuleResponseDto {

    private String id;
    private String code;
    private String name;
    private String description;
    private boolean active;
    private boolean systemModule;
    private List<ModulePrivilege> privileges;
    private Instant createdAt;
    private Instant updatedAt;

    public ModuleResponseDto() {
    }

    public ModuleResponseDto(String id, String code, String name, String description,
                             boolean active, boolean systemModule, List<ModulePrivilege> privileges,
                             Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.description = description;
        this.active = active;
        this.systemModule = systemModule;
        this.privileges = privileges;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
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
        return privileges;
    }

    public void setPrivileges(List<ModulePrivilege> privileges) {
        this.privileges = privileges;
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
