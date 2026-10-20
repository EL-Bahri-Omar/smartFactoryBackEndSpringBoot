package com.smartfactory.entity;

/**
 * Niveaux de priorité d'une intervention de maintenance (Sprint 6).
 */
public enum InterventionPriority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public static InterventionPriority fromString(String val) {
        if (val == null || val.isBlank()) {
            return null;
        }
        try {
            return InterventionPriority.valueOf(val.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
