package com.smartfactory.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartfactory.security.Permission;
import com.smartfactory.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Document(collection = "users")
@JsonIgnoreProperties(ignoreUnknown = true)
public class User {

    @Id
    private String id;

    @NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    @Indexed(unique = true)
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    private Role role = Role.OPERATOR;
    private String customRoleName;
    private String status = "ACTIVE"; // ACTIVE, INACTIVE
    private boolean emailVerified = true;

    /**
     * Préférence de notification push de l'utilisateur (Section 5).
     * true = notifications autorisées (par défaut), false = désactivées par l'utilisateur.
     */
    private boolean notificationsEnabled = true;

    /**
     * Privilèges explicitement accordés à cet utilisateur (en plus de son rôle).
     */
    private Set<Permission> customGrantedPermissions = new HashSet<>();

    /**
     * Privilèges explicitement révoqués pour cet utilisateur (même si présents dans son rôle).
     */
    private Set<Permission> customRevokedPermissions = new HashSet<>();

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public User() {
    }

    public User(String firstName, String lastName, String email, String password, Role role, String status) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.password = password;
        this.role = role != null ? role : Role.OPERATOR;
        this.status = status != null ? status : "ACTIVE";
    }

    /**
     * Calcule l'ensemble des permissions effectives de l'utilisateur :
     * 1. Permissions par défaut héritées du rôle
     * 2. + Permissions explicitement accordées (customGrantedPermissions)
     * 3. - Permissions explicitement refusées (customRevokedPermissions)
     */
    @JsonProperty(value = "effectivePermissions", access = JsonProperty.Access.READ_ONLY)
    public Set<Permission> getEffectivePermissions() {
        Set<Permission> effective = new HashSet<>();
        if (role != null && role.getDefaultPermissions() != null) {
            effective.addAll(role.getDefaultPermissions());
        }
        if (customGrantedPermissions != null) {
            effective.addAll(customGrantedPermissions);
        }
        if (customRevokedPermissions != null) {
            effective.removeAll(customRevokedPermissions);
        }
        return Collections.unmodifiableSet(effective);
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public void setEffectivePermissions(Set<Permission> effectivePermissions) {
        // no-op for deserialization safety
    }

    /**
     * Vérifie si l'utilisateur possède une permission spécifique effective.
     */
    public boolean hasEffectivePermission(Permission permission) {
        if (permission == null) {
            return false;
        }
        // Refus explicite prioritaire
        if (customRevokedPermissions != null && customRevokedPermissions.contains(permission)) {
            return false;
        }
        // Accord explicite prioritaire
        if (customGrantedPermissions != null && customGrantedPermissions.contains(permission)) {
            return true;
        }
        // Héritage par défaut du rôle
        return role != null && role.hasDefaultPermission(permission);
    }

    /**
     * Synchronise la checklist des privilèges pour cet utilisateur.
     * Détermine automatiquement les autorisations et révocations personnalisées
     * par rapport au rôle par défaut.
     */
    public void applyPermissionsChecklist(Collection<Permission> checkedPermissions) {
        Set<Permission> target = checkedPermissions != null ? new HashSet<>(checkedPermissions) : Collections.emptySet();
        Set<Permission> roleDefaults = role != null ? role.getDefaultPermissions() : Collections.emptySet();

        Set<Permission> newGranted = new HashSet<>();
        Set<Permission> newRevoked = new HashSet<>();

        for (Permission p : Permission.values()) {
            boolean isChecked = target.contains(p);
            boolean isRoleDefault = roleDefaults.contains(p);

            if (isChecked && !isRoleDefault) {
                newGranted.add(p);
            } else if (!isChecked && isRoleDefault) {
                newRevoked.add(p);
            }
        }

        this.customGrantedPermissions = newGranted;
        this.customRevokedPermissions = newRevoked;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public String getCustomRoleName() {
        return customRoleName;
    }

    public void setCustomRoleName(String customRoleName) {
        this.customRoleName = customRoleName;
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getEffectiveRoleName() {
        if (customRoleName != null && !customRoleName.trim().isEmpty()) {
            return customRoleName.trim().toUpperCase();
        }
        return role != null ? role.name() : "OPERATOR";
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public void setEffectiveRoleName(String effectiveRoleName) {
        // no-op for deserialization safety
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public Set<Permission> getCustomGrantedPermissions() {
        return customGrantedPermissions;
    }

    public void setCustomGrantedPermissions(Set<Permission> customGrantedPermissions) {
        this.customGrantedPermissions = customGrantedPermissions != null ? customGrantedPermissions : new HashSet<>();
    }

    public Set<Permission> getCustomRevokedPermissions() {
        return customRevokedPermissions;
    }

    public void setCustomRevokedPermissions(Set<Permission> customRevokedPermissions) {
        this.customRevokedPermissions = customRevokedPermissions != null ? customRevokedPermissions : new HashSet<>();
    }

    public boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }

    public void setNotificationsEnabled(boolean notificationsEnabled) {
        this.notificationsEnabled = notificationsEnabled;
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getUserId() {
        return id;
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public void setUserId(String userId) {
        // no-op for deserialization safety
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public Set<Permission> getRoleDefaultPermissions() {
        return role != null ? role.getDefaultPermissions() : Collections.emptySet();
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public void setRoleDefaultPermissions(Set<Permission> roleDefaultPermissions) {
        // no-op for deserialization safety
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
