package com.smartfactory.entity;

/**
 * Statuts possibles d'une intervention de maintenance (Sprint 6).
 */
public enum InterventionStatus {
    PENDING,
    ASSIGNED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED;

    public static InterventionStatus fromString(String val) {
        if (val == null || val.isBlank()) {
            return null;
        }
        try {
            return InterventionStatus.valueOf(val.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
