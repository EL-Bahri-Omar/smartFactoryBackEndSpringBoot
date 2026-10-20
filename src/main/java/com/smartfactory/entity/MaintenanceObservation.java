package com.smartfactory.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * Observation consignée lors d'une intervention de maintenance (Sprint 6 - US47).
 */
public class MaintenanceObservation {

    private String id;
    private String content;
    private String authorId;
    private String authorName;
    private String category; // SAFETY, PERFORMANCE, OPERATING_CONDITION, GENERAL
    private Instant createdAt;

    public MaintenanceObservation() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
    }

    public MaintenanceObservation(String content, String authorId, String authorName, String category) {
        this.id = UUID.randomUUID().toString();
        this.content = content;
        this.authorId = authorId;
        this.authorName = authorName;
        this.category = category != null ? category : "GENERAL";
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getAuthorId() { return authorId; }
    public void setAuthorId(String authorId) { this.authorId = authorId; }

    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
