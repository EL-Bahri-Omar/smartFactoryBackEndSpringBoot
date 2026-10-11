package com.smartfactory.dto;

import com.smartfactory.security.Permission;
import com.smartfactory.security.Role;

import java.util.Set;

/**
 * DTO représentant l'état détaillé des permissions d'un utilisateur.
 */
public class UserPermissionsDto {

    private String userId;
    private String email;
    private Role role;
    private Set<Permission> roleDefaultPermissions;
    private Set<Permission> customGrantedPermissions;
    private Set<Permission> customRevokedPermissions;
    private Set<Permission> effectivePermissions;

    public UserPermissionsDto() {
    }

    public UserPermissionsDto(String userId, String email, Role role,
                              Set<Permission> roleDefaultPermissions,
                              Set<Permission> customGrantedPermissions,
                              Set<Permission> customRevokedPermissions,
                              Set<Permission> effectivePermissions) {
        this.userId = userId;
        this.email = email;
        this.role = role;
        this.roleDefaultPermissions = roleDefaultPermissions;
        this.customGrantedPermissions = customGrantedPermissions;
        this.customRevokedPermissions = customRevokedPermissions;
        this.effectivePermissions = effectivePermissions;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public Set<Permission> getRoleDefaultPermissions() {
        return roleDefaultPermissions;
    }

    public void setRoleDefaultPermissions(Set<Permission> roleDefaultPermissions) {
        this.roleDefaultPermissions = roleDefaultPermissions;
    }

    public Set<Permission> getCustomGrantedPermissions() {
        return customGrantedPermissions;
    }

    public void setCustomGrantedPermissions(Set<Permission> customGrantedPermissions) {
        this.customGrantedPermissions = customGrantedPermissions;
    }

    public Set<Permission> getCustomRevokedPermissions() {
        return customRevokedPermissions;
    }

    public void setCustomRevokedPermissions(Set<Permission> customRevokedPermissions) {
        this.customRevokedPermissions = customRevokedPermissions;
    }

    public Set<Permission> getEffectivePermissions() {
        return effectivePermissions;
    }

    public void setEffectivePermissions(Set<Permission> effectivePermissions) {
        this.effectivePermissions = effectivePermissions;
    }
}
