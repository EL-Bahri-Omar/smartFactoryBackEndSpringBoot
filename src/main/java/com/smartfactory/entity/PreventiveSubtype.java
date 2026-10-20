package com.smartfactory.entity;

/**
 * Sous-types de maintenance préventive obligatoires (Sprint 6).
 *
 * - SYSTEMATIC : Réalisée selon une périodicité calendaire ou horaire définie (ex: 500h ou 3 mois).
 * - CONDITIONAL : Déclenchée selon l'état réel de la machine, les mesures des capteurs ou indicateurs d'usure.
 */
public enum PreventiveSubtype {
    SYSTEMATIC,
    CONDITIONAL;

    public static PreventiveSubtype fromString(String val) {
        if (val == null || val.isBlank()) {
            return null;
        }
        try {
            return PreventiveSubtype.valueOf(val.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
