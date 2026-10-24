package com.smartfactory.dto;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Requête d'assignation d'un ou plusieurs techniciens à une intervention (Sprint 6 - US43).
 * Prend en charge l'assignation multiple via technicianIds tout en maintenant
 * la compatibilité ascendante totale avec le champ unique technicianId.
 */
public class InterventionAssignRequest {

    private List<String> technicianIds = new ArrayList<>();

    private String technicianId;

    public InterventionAssignRequest() {
    }

    public InterventionAssignRequest(String technicianId) {
        setTechnicianId(technicianId);
    }

    public InterventionAssignRequest(List<String> technicianIds) {
        setTechnicianIds(technicianIds);
    }

    public List<String> getTechnicianIds() {
        if (technicianIds == null) {
            technicianIds = new ArrayList<>();
        }
        if (technicianIds.isEmpty() && StringUtils.hasText(technicianId)) {
            technicianIds.add(technicianId.trim());
        }
        return technicianIds;
    }

    public void setTechnicianIds(List<String> technicianIds) {
        if (technicianIds != null) {
            this.technicianIds = technicianIds.stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .distinct()
                    .collect(Collectors.toCollection(ArrayList::new));
            this.technicianId = this.technicianIds.isEmpty() ? null : this.technicianIds.get(0);
        } else {
            this.technicianIds = new ArrayList<>();
            this.technicianId = null;
        }
    }

    public String getTechnicianId() {
        if (StringUtils.hasText(technicianId)) {
            return technicianId;
        }
        return (technicianIds != null && !technicianIds.isEmpty()) ? technicianIds.get(0) : null;
    }

    public void setTechnicianId(String technicianId) {
        this.technicianId = technicianId;
        if (StringUtils.hasText(technicianId)) {
            if (this.technicianIds == null) {
                this.technicianIds = new ArrayList<>();
            }
            String clean = technicianId.trim();
            if (!this.technicianIds.contains(clean)) {
                this.technicianIds.add(clean);
            }
        }
    }
}
