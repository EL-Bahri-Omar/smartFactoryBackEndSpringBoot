package com.smartfactory.security;

import com.smartfactory.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.*;

public class UserPrincipal implements UserDetails {

    private final String id;
    private final String email;
    private final String password;
    private final Role role;
    private final String status;
    private final Set<Permission> effectivePermissions;
    private final Collection<? extends GrantedAuthority> authorities;

    public UserPrincipal(String id, String email, String password, Role role, String status,
                         Set<Permission> effectivePermissions,
                         Collection<? extends GrantedAuthority> authorities) {
        this.id = id;
        this.email = email;
        this.password = password;
        this.role = role;
        this.status = status;
        this.effectivePermissions = effectivePermissions != null ? effectivePermissions : Collections.emptySet();
        this.authorities = authorities;
    }

    public static UserPrincipal create(User user) {
        return create(user, null);
    }

    public static UserPrincipal create(User user, Set<String> dynamicRolePrivileges) {
        Set<GrantedAuthority> authorities = new HashSet<>();

        // Rôle effectif avec préfixe ROLE_ pour Spring Security
        String effectiveRoleName = user.getEffectiveRoleName();
        if (effectiveRoleName != null && !effectiveRoleName.trim().isEmpty()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + effectiveRoleName.trim().toUpperCase()));
            authorities.add(new SimpleGrantedAuthority(effectiveRoleName.trim().toUpperCase()));
        }

        Set<Permission> effectivePermissions = new HashSet<>();

        // Privilèges de base hérités du rôle
        if (dynamicRolePrivileges != null && !dynamicRolePrivileges.isEmpty()) {
            for (String privCode : dynamicRolePrivileges) {
                if (privCode == null || privCode.isBlank()) continue;
                String normalized = privCode.trim().toUpperCase();
                Permission p = Permission.fromCode(normalized);
                if (p != null) {
                    effectivePermissions.add(p);
                }
                authorities.add(new SimpleGrantedAuthority(normalized));
                authorities.add(new SimpleGrantedAuthority(normalized.replace(":", "_")));

                // Pont sémantique vers les permissions système existantes
                mapDynamicToSystemAuthority(normalized, authorities);
            }
        } else if (user.getRole() != null && user.getRole().getDefaultPermissions() != null) {
            effectivePermissions.addAll(user.getRole().getDefaultPermissions());
        }

        // Privilèges explicitement accordés à l'utilisateur
        if (user.getCustomGrantedPermissions() != null) {
            effectivePermissions.addAll(user.getCustomGrantedPermissions());
        }

        // Privilèges explicitement révoqués pour l'utilisateur
        if (user.getCustomRevokedPermissions() != null) {
            effectivePermissions.removeAll(user.getCustomRevokedPermissions());
        }

        // Enregistrer toutes les permissions effectives comme GrantedAuthority
        for (Permission perm : effectivePermissions) {
            authorities.add(new SimpleGrantedAuthority(perm.name()));
        }

        return new UserPrincipal(
                user.getId(),
                user.getEmail(),
                user.getPassword(),
                user.getRole(),
                user.getStatus(),
                effectivePermissions,
                Collections.unmodifiableSet(authorities)
        );
    }

    public String getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    public String getStatus() {
        return status;
    }

    public Set<Permission> getEffectivePermissions() {
        return effectivePermissions;
    }

    public boolean hasPermission(Permission permission) {
        return effectivePermissions != null && effectivePermissions.contains(permission);
    }

    public boolean hasPermission(String permissionCode) {
        Permission p = Permission.fromCode(permissionCode);
        return p != null && hasPermission(p);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return "ACTIVE".equalsIgnoreCase(status);
    }

    private static void mapDynamicToSystemAuthority(String code, Set<GrantedAuthority> authorities) {
        if (code == null) return;
        switch (code) {
            case "USER:CONSULTER":
                authorities.add(new SimpleGrantedAuthority("USER_READ"));
                break;
            case "USER:AJOUTER":
            case "USER:MODIFIER":
            case "USER:SUPPRIMER":
                authorities.add(new SimpleGrantedAuthority("USER_WRITE"));
                break;
            case "USER:GERER_PERMISSIONS":
                authorities.add(new SimpleGrantedAuthority("USER_PERMISSIONS_MANAGE"));
                break;
            case "MACHINE:CONSULTER":
                authorities.add(new SimpleGrantedAuthority("MACHINE_READ"));
                break;
            case "MACHINE:AJOUTER":
            case "MACHINE:MODIFIER":
            case "MACHINE:SUPPRIMER":
                authorities.add(new SimpleGrantedAuthority("MACHINE_WRITE"));
                break;
            case "SENSOR:CONSULTER":
                authorities.add(new SimpleGrantedAuthority("SENSOR_READ"));
                break;
            case "SENSOR:HISTORIQUE":
                authorities.add(new SimpleGrantedAuthority("READINGS_READ"));
                authorities.add(new SimpleGrantedAuthority("SENSOR_HISTORY_READ"));
                break;
            case "THRESHOLD:CONSULTER":
                authorities.add(new SimpleGrantedAuthority("THRESHOLD_READ"));
                break;
            case "THRESHOLD:MODIFIER":
                authorities.add(new SimpleGrantedAuthority("THRESHOLD_WRITE"));
                break;
            case "ALERT:CONSULTER":
                authorities.add(new SimpleGrantedAuthority("ALERT_READ"));
                break;
            case "ALERT:ACQUITTER":
                authorities.add(new SimpleGrantedAuthority("ALERT_ACKNOWLEDGE"));
                break;
            case "MAINTENANCE:CONSULTER":
                authorities.add(new SimpleGrantedAuthority("MAINTENANCE_READ"));
                break;
            case "MAINTENANCE:CREER":
            case "MAINTENANCE:ASSIGNER":
            case "MAINTENANCE:DIAGNOSTIQUER":
            case "MAINTENANCE:CLOTURER":
                authorities.add(new SimpleGrantedAuthority("MAINTENANCE_WRITE"));
                break;
            case "ZONE:CONSULTER":
                authorities.add(new SimpleGrantedAuthority("ZONE_READ"));
                break;
            case "ZONE:AJOUTER":
            case "ZONE:MODIFIER":
            case "ZONE:SUPPRIMER":
                authorities.add(new SimpleGrantedAuthority("ZONE_WRITE"));
                break;
            case "GROUP:CONSULTER":
                authorities.add(new SimpleGrantedAuthority("GROUP_READ"));
                break;
            case "GROUP:AJOUTER":
            case "GROUP:MODIFIER":
            case "GROUP:SUPPRIMER":
                authorities.add(new SimpleGrantedAuthority("GROUP_WRITE"));
                break;
            case "DASHBOARD:CONSULTER":
                authorities.add(new SimpleGrantedAuthority("DASHBOARD_READ"));
                break;
            case "ROLE:CONSULTER":
                authorities.add(new SimpleGrantedAuthority("ROLE_READ"));
                break;
            case "ROLE:AJOUTER":
                authorities.add(new SimpleGrantedAuthority("ROLE_CREATE"));
                break;
            case "ROLE:MODIFIER":
                authorities.add(new SimpleGrantedAuthority("ROLE_UPDATE"));
                break;
            case "ROLE:SUPPRIMER":
                authorities.add(new SimpleGrantedAuthority("ROLE_DELETE"));
                break;
            default:
                break;
        }
    }
}
