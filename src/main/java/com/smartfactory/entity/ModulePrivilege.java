package com.smartfactory.entity;

import jakarta.validation.constraints.NotBlank;

import java.io.Serializable;
import java.util.Objects;

/**
 * Privilège unitaire rattaché à un module fonctionnel (Partie 2.4).
 * Identifié de manière stable et déterministe par un code composé : {MODULE_CODE}:{ACTION}.
 * Exemple : USER:CONSULTER, MACHINE:SUPPRIMER, ALERT:ACQUITTER.
 */
public class ModulePrivilege implements Serializable {

    @NotBlank(message = "L'action du privilège est obligatoire")
    private String action; // ex: CONSULTER, AJOUTER, MODIFIER, SUPPRIMER, ACQUITTER

    private String code; // ex: USER:CONSULTER

    private String name;

    private String description;

    private String category;

    public ModulePrivilege() {
    }

    public ModulePrivilege(String action, String code, String name, String description) {
        this.action = action != null ? action.trim().toUpperCase() : null;
        this.code = code != null ? code.trim().toUpperCase() : null;
        this.name = name;
        this.description = description;
    }

    public ModulePrivilege(String action, String code, String name, String description, String category) {
        this.action = action != null ? action.trim().toUpperCase() : null;
        this.code = code != null ? code.trim().toUpperCase() : null;
        this.name = name;
        this.description = description;
        this.category = category;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action != null ? action.trim().toUpperCase() : null;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code != null ? code.trim().toUpperCase() : null;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ModulePrivilege that = (ModulePrivilege) o;
        return Objects.equals(code, that.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }
}
