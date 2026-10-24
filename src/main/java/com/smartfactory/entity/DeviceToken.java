package com.smartfactory.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Jeton de terminal mobile/web pour les notifications Firebase Cloud Messaging (US40).
 */
@Document(collection = "device_tokens")
public class DeviceToken {

    @Id
    private String id;

    @Indexed
    private String userId;

    @Indexed(unique = true)
    private String token;

    private String deviceType; // ANDROID, IOS, WEB

    private String deviceId;

    private boolean active = true;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant lastActiveAt;

    public DeviceToken() {
    }

    public DeviceToken(String userId, String token, String deviceType) {
        this.userId = userId;
        this.token = token;
        this.deviceType = deviceType != null ? deviceType.toUpperCase() : "ANDROID";
        this.active = true;
        this.createdAt = Instant.now();
        this.lastActiveAt = Instant.now();
    }

    public DeviceToken(String userId, String token, String deviceType, String deviceId) {
        this.userId = userId;
        this.token = token;
        this.deviceType = deviceType != null ? deviceType.toUpperCase() : "ANDROID";
        this.deviceId = deviceId;
        this.active = true;
        this.createdAt = Instant.now();
        this.lastActiveAt = Instant.now();
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

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(Instant lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }
}
