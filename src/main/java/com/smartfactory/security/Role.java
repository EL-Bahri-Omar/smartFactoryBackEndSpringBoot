package com.smartfactory.security;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Rôles métier servant de modèle de permissions par défaut.
 * Chaque utilisateur hérite des permissions par défaut de son rôle,
 * qui peuvent ensuite être personnalisées individuellement sans modifier le rôle.
 */
public enum Role {
    ADMIN(
            "Administrateur système",
            EnumSet.allOf(Permission.class)
    ),

    OPERATOR(
            "Opérateur de ligne",
            EnumSet.of(
                    Permission.MACHINE_READ,
                    Permission.READINGS_READ,
                    Permission.SENSOR_READ,
                    Permission.SENSOR_HISTORY_READ,
                    Permission.DASHBOARD_READ,
                    Permission.ZONE_READ,
                    Permission.GROUP_READ
            )
    ),

    TECHNICIAN(
            "Technicien de maintenance",
            EnumSet.of(
                    Permission.MACHINE_READ,
                    Permission.READINGS_READ,
                    Permission.SENSOR_READ,
                    Permission.SENSOR_HISTORY_READ,
                    Permission.DASHBOARD_READ,
                    Permission.ALERT_READ,
                    Permission.ALERT_ACKNOWLEDGE,
                    Permission.THRESHOLD_READ,
                    Permission.ZONE_READ,
                    Permission.GROUP_READ,
                    Permission.MAINTENANCE_READ,
                    Permission.MAINTENANCE_WRITE
            )
    ),

    RESPONSABLE_INDUSTRIEL(
            "Responsable industriel",
            EnumSet.of(
                    Permission.MACHINE_READ,
                    Permission.MACHINE_WRITE,
                    Permission.MACHINE_CREATE,
                    Permission.MACHINE_UPDATE,
                    Permission.MACHINE_DELETE,
                    Permission.READINGS_READ,
                    Permission.SENSOR_READ,
                    Permission.SENSOR_HISTORY_READ,
                    Permission.DASHBOARD_READ,
                    Permission.THRESHOLD_READ,
                    Permission.THRESHOLD_WRITE,
                    Permission.ALERT_READ,
                    Permission.ALERT_ACKNOWLEDGE,
                    Permission.ZONE_READ,
                    Permission.GROUP_READ,
                    Permission.MAINTENANCE_READ,
                    Permission.MAINTENANCE_WRITE
            )
    );

    private final String label;
    private final Set<Permission> defaultPermissions;

    Role(String label, Set<Permission> defaultPermissions) {
        this.label = label;
        this.defaultPermissions = Collections.unmodifiableSet(defaultPermissions);
    }

    public String getLabel() {
        return label;
    }

    public Set<Permission> getDefaultPermissions() {
        return defaultPermissions;
    }

    public boolean hasDefaultPermission(Permission permission) {
        return defaultPermissions.contains(permission);
    }
}
