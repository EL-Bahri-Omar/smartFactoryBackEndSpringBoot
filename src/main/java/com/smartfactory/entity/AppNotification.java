package com.smartfactory.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Notification applicative persistée pour un utilisateur (Sprint 5 - US40).
 */
@Document(collection = "notifications")
public class AppNotification {

    @Id
    private String id;

    @Indexed
    private String userId;

    private String alertId;

    private String machineId;

    private String title;

    private String message;

    private String type; // ALERT_CRITICAL, ALERT_HIGH, MAINTENANCE_ASSIGNED, INFO

    private boolean isRead = false;

    @CreatedDate
    private Instant createdAt;

    public AppNotification() {
    }

    public AppNotification(String userId, String alertId, String machineId, String title, String message, String type) {
        this.userId = userId;
        this.alertId = alertId;
        this.machineId = machineId;
        this.title = title;
        this.message = message;
        this.type = type != null ? type : "INFO";
        this.isRead = false;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAlertId() {
        return alertId;
    }

    public void setAlertId(String alertId) {
        this.alertId = alertId;
    }

    public String getMachineId() {
        return machineId;
    }

    public void setMachineId(String machineId) {
        this.machineId = machineId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("read")
    public boolean isRead() {
        return isRead;
    }

    public void setRead(boolean read) {
        isRead = read;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("isRead")
    public boolean getIsRead() {
        return isRead;
    }

    public void setIsRead(boolean read) {
        isRead = read;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
