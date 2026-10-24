package com.smartfactory.dto;

import jakarta.validation.constraints.NotBlank;

public class DeviceTokenRequest {

    @NotBlank(message = "Le token de terminal est obligatoire")
    private String token;

    private String deviceType; // ANDROID, IOS, WEB

    private String deviceId;

    public DeviceTokenRequest() {
    }

    public DeviceTokenRequest(String token) {
        this.token = token;
    }

    public DeviceTokenRequest(String token, String deviceType) {
        this.token = token;
        this.deviceType = deviceType;
    }

    public DeviceTokenRequest(String token, String deviceType, String deviceId) {
        this.token = token;
        this.deviceType = deviceType;
        this.deviceId = deviceId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
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
}
