package com.smartfactory.dto;

import java.util.Set;

public class RoleUpdateRequest {

    private String description;
    private Set<String> privileges;

    public RoleUpdateRequest() {
    }

    public RoleUpdateRequest(String description, Set<String> privileges) {
        this.description = description;
        this.privileges = privileges;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Set<String> getPrivileges() {
        return privileges;
    }

    public void setPrivileges(Set<String> privileges) {
        this.privileges = privileges;
    }
}
