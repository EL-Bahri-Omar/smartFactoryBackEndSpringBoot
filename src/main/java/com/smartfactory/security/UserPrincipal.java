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
        Set<GrantedAuthority> authorities = new HashSet<>();

        // Rôle avec préfixe ROLE_ pour rétrocompatibilité
        if (user.getRole() != null) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        }

        // Permissions effectives (rôle + accordées - révoquées)
        Set<Permission> effectivePermissions = user.getEffectivePermissions();
        if (effectivePermissions != null) {
            for (Permission perm : effectivePermissions) {
                authorities.add(new SimpleGrantedAuthority(perm.name()));
            }
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
}
