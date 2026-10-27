package com.smartfactory.entity;

import jakarta.validation.constraints.NotBlank;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Entité persistée représentant un rôle dynamique au sein du système SmartFactory.
 * Permet à l'administrateur de créer, modifier et configurer de nouveaux rôles
 * et leurs privilèges associés sans recompiler l'application.
 */
@Document(collection = "roles")
public class RoleDocument {

    @Id
    private String id;

    @NotBlank(message = "Le nom du rôle est obligatoire")
    @Indexed(unique = true)
    private String name;

    private String description;

    private Set<String> privileges = new HashSet<>();

    private boolean systemRole = false;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public RoleDocument() {
    }

    public RoleDocument(String name, String description, Set<String> privileges, boolean systemRole) {
        this.name = name != null ? name.trim().toUpperCase() : null;
        this.description = description;
        this.privileges = privileges != null ? new HashSet<>(privileges) : new HashSet<>();
        this.systemRole = systemRole;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name != null ? name.trim().toUpperCase() : null;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Set<String> getPrivileges() {
        return privileges != null ? Collections.unmodifiableSet(privileges) : Collections.emptySet();
    }

    public void setPrivileges(Set<String> privileges) {
        this.privileges = privileges != null ? new HashSet<>(privileges) : new HashSet<>();
    }

    public boolean isSystemRole() {
        return systemRole;
    }

    public void setSystemRole(boolean systemRole) {
        this.systemRole = systemRole;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @org.springframework.data.annotation.Transient
    private long userCount;

    public long getUserCount() {
        return userCount;
    }

    public void setUserCount(long userCount) {
        this.userCount = userCount;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
