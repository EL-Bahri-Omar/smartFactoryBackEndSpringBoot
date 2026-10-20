package com.smartfactory.entity;

/**
 * Types de maintenance obligatoires (Sprint 6).
 *
 * 1. CORRECTIVE : Intervention directe pendant ou après une panne, pour corriger et remettre en état.
 * 2. PREVENTIVE : Maintenance effectuée pour réduire le risque de panne avant qu'elle ne survienne.
 *    (Sous-types : SYSTEMATIC ou CONDITIONAL).
 * 3. IMPROVEMENT : Intervention destinée à améliorer les performances, la fiabilité ou la sécurité.
 */
public enum MaintenanceType {
    CORRECTIVE,
    PREVENTIVE,
    IMPROVEMENT;

    public static MaintenanceType fromString(String val) {
        if (val == null || val.isBlank()) {
            return null;
        }
        try {
            return MaintenanceType.valueOf(val.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
