package com.smartfactory.security;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Catalogue des permissions granulaires de la plateforme SmartFactory.
 * Chaque permission correspond à une opération sensible spécifique du backend.
 */
public enum Permission {

    // === GESTION DES MACHINES ===
    MACHINE_READ("MACHINE_READ", "Consulter les machines et leurs capteurs", "MACHINES"),
    MACHINE_WRITE("MACHINE_WRITE", "Créer, modifier et supprimer des machines", "MACHINES"),
    MACHINE_CREATE("MACHINE_CREATE", "Créer de nouvelles machines", "MACHINES"),
    MACHINE_UPDATE("MACHINE_UPDATE", "Modifier les caractéristiques des machines", "MACHINES"),
    MACHINE_DELETE("MACHINE_DELETE", "Supprimer des machines", "MACHINES"),

    // === TÉLÉMÉTRIE ET CAPTEURS ===
    READINGS_READ("READINGS_READ", "Consulter l'historique et les lectures de télémétrie", "READINGS"),
    SENSOR_READ("SENSOR_READ", "Consulter la télémétrie et les capteurs en temps réel", "SENSORS"),
    SENSOR_HISTORY_READ("SENSOR_HISTORY_READ", "Consulter l'historique des mesures des capteurs", "SENSORS"),

    // === DASHBOARD ET SUPERVISION ===
    DASHBOARD_READ("DASHBOARD_READ", "Consulter le tableau de bord et les KPI de supervision", "DASHBOARD"),

    // === SEUILS DE SURVEILLANCE ===
    THRESHOLD_READ("THRESHOLD_READ", "Consulter les seuils de surveillance des machines", "THRESHOLDS"),
    THRESHOLD_WRITE("THRESHOLD_WRITE", "Créer, modifier et supprimer les seuils de surveillance", "THRESHOLDS"),

    // === ALERTES ===
    ALERT_READ("ALERT_READ", "Consulter les alertes de surveillance", "ALERTS"),
    ALERT_ACKNOWLEDGE("ALERT_ACKNOWLEDGE", "Acquitter et résoudre les alertes", "ALERTS"),

    // === UTILISATEURS ET SÉCURITÉ ===
    USER_READ("USER_READ", "Consulter les utilisateurs du système", "USERS"),
    USER_WRITE("USER_WRITE", "Créer, modifier et supprimer des comptes utilisateurs", "USERS"),
    USER_CREATE("USER_CREATE", "Créer de nouveaux comptes utilisateurs", "USERS"),
    USER_UPDATE("USER_UPDATE", "Modifier les informations des utilisateurs", "USERS"),
    USER_DELETE("USER_DELETE", "Supprimer des comptes utilisateurs", "USERS"),
    USER_PERMISSIONS_MANAGE("USER_PERMISSIONS_MANAGE", "Attribuer et personnaliser les privilèges des utilisateurs", "USERS"),

    // === RÔLES ET PRIVILÈGES ===
    ROLE_READ("ROLE_READ", "Consulter les rôles et leurs privilèges associés", "ROLES"),
    ROLE_CREATE("ROLE_CREATE", "Créer de nouveaux rôles personnalisés", "ROLES"),
    ROLE_UPDATE("ROLE_UPDATE", "Modifier les rôles et leurs privilèges", "ROLES"),
    ROLE_DELETE("ROLE_DELETE", "Supprimer des rôles personnalisés", "ROLES"),

    // === GROUPES D'OPÉRATEURS ===
    GROUP_READ("GROUP_READ", "Consulter les groupes d'opérateurs", "GROUPS"),
    GROUP_WRITE("GROUP_WRITE", "Créer, modifier des groupes et affecter les membres", "GROUPS"),

    // === ZONES INDUSTRIELLES ===
    ZONE_READ("ZONE_READ", "Consulter les zones de l'usine", "ZONES"),
    ZONE_WRITE("ZONE_WRITE", "Créer, modifier et supprimer des zones", "ZONES");

    private final String code;
    private final String description;
    private final String category;

    Permission(String code, String description, String category) {
        this.code = code;
        this.description = description;
        this.category = category;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public static Permission fromCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return null;
        }
        String normalized = code.trim().toUpperCase();
        for (Permission p : values()) {
            if (p.name().equalsIgnoreCase(normalized) || p.code.equalsIgnoreCase(normalized)) {
                return p;
            }
        }
        return null;
    }

    public static Set<Permission> all() {
        Set<Permission> allPerms = new HashSet<>();
        Collections.addAll(allPerms, values());
        return Collections.unmodifiableSet(allPerms);
    }
}
