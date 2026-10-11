package com.smartfactory.dto;

import com.smartfactory.security.Permission;

import java.util.Set;

/**
 * Requête de mise à jour des permissions d'un utilisateur.
 * Supporte soit la checklist globale des permissions souhaitées,
 * soit la spécification explicite des privilèges accordés et révoqués.
 */
public class UpdatePermissionsRequest {

    /**
     * Checklist complète des permissions effectives souhaitées pour l'utilisateur.
     * Si fourni, recalcule automatiquement granted/revoked par rapport au rôle.
     */
    private Set<Permission> permissions;

    /**
     * Privilèges explicitement accordés (optionnel, si modification différentielle).
     */
    private Set<Permission> grantedPermissions;

    /**
     * Privilèges explicitement révoqués (optionnel, si modification différentielle).
     */
    private Set<Permission> revokedPermissions;

    public UpdatePermissionsRequest() {
    }

    public Set<Permission> getPermissions() {
        return permissions;
    }

    public void setPermissions(Set<Permission> permissions) {
        this.permissions = permissions;
    }

    public Set<Permission> getGrantedPermissions() {
        return grantedPermissions;
    }

    public void setGrantedPermissions(Set<Permission> grantedPermissions) {
        this.grantedPermissions = grantedPermissions;
    }

    public Set<Permission> getRevokedPermissions() {
        return revokedPermissions;
    }

    public void setRevokedPermissions(Set<Permission> revokedPermissions) {
        this.revokedPermissions = revokedPermissions;
    }
}
