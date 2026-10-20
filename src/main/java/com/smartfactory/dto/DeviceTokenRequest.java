package com.smartfactory.dto;

import jakarta.validation.constraints.NotBlank;

public class DeviceTokenRequest {

    @NotBlank(message = "Le token de terminal est obligatoire")
    private String token;

    private String deviceType; // ANDROID, IOS, WEB

    public DeviceTokenRequest() {
    }

    public DeviceTokenRequest(String token, String deviceType) {
        this.token = token;
        this.deviceType = deviceType;
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
