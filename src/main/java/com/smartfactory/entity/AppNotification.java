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

    @Indexed
    private String alertId;

    @Indexed
    private String interventionId;

    private String machineId;

    private String title;

    private String message;

    private String type; // ALERT_CRITICAL, ALERT_WARNING, MAINTENANCE_ASSIGNED, MAINTENANCE_UPDATED, MAINTENANCE_COMPLETED, GROUP_MEMBER_ADDED, GROUP_MEMBER_REMOVED, INFO

    private boolean isRead = false;

    private java.util.Map<String, Object> data = new java.util.HashMap<>();

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

    public AppNotification(String userId, String alertId, String interventionId, String machineId, String title, String message, String type, java.util.Map<String, Object> data) {
        this.userId = userId;
        this.alertId = alertId;
        this.interventionId = interventionId;
        this.machineId = machineId;
        this.title = title;
        this.message = message;
        this.type = type != null ? type : "INFO";
        this.isRead = false;
        this.data = data != null ? new java.util.HashMap<>(data) : new java.util.HashMap<>();
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

    public String getInterventionId() {
        return interventionId;
    }

    public void setInterventionId(String interventionId) {
        this.interventionId = interventionId;
    }

    public java.util.Map<String, Object> getData() {
        return data != null ? data : new java.util.HashMap<>();
    }

    public void setData(java.util.Map<String, Object> data) {
        this.data = data != null ? new java.util.HashMap<>(data) : new java.util.HashMap<>();
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
