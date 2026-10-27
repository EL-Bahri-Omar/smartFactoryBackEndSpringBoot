package com.smartfactory.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfactory.entity.DeviceToken;
import com.smartfactory.entity.User;
import com.smartfactory.repository.DeviceTokenRepository;
import com.smartfactory.repository.UserRepository;
import com.smartfactory.security.JwtService;
import com.smartfactory.security.Role;
import com.smartfactory.service.PushNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SettingsAndNotificationPreferencesTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DeviceTokenRepository deviceTokenRepository;

    @Autowired
    private PushNotificationService pushNotificationService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    private User technician;
    private String technicianToken;

    @BeforeEach
    void setUp() {
        deviceTokenRepository.deleteAll();
        userRepository.deleteAll();

        technician = new User("Lucas", "Tech", "lucas.tech@smartfactory.com", passwordEncoder.encode("pass123"), Role.TECHNICIAN, "ACTIVE");
        technician.setNotificationsEnabled(true);
        technician = userRepository.save(technician);

        technicianToken = jwtService.generateToken(technician);
    }

    @Test
    @DisplayName("Preferences: Récupération et bascule des préférences de notification push via /api/settings")
    void testToggleNotificationPreferences() throws Exception {
        // 1. Consulter les préférences initiales
        mockMvc.perform(get("/api/settings")
                        .header("Authorization", "Bearer " + technicianToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationsEnabled", is(true)));

        // 2. Désactiver les notifications push
        mockMvc.perform(patch("/api/settings")
                        .header("Authorization", "Bearer " + technicianToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("notificationsEnabled", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationsEnabled", is(false)));

        // 3. Vérifier la persistance en base de données
        User updated = userRepository.findById(technician.getId()).orElseThrow();
        assertThat(updated.isNotificationsEnabled()).isFalse();

        // 4. Vérifier que PushNotificationService respecte la désactivation
        deviceTokenRepository.save(new DeviceToken(technician.getId(), "fake-fcm-token-12345", "ANDROID", "device-001"));
        pushNotificationService.sendPushNotification(
                technician.getId(),
                "Alerte Test",
                "Message de test",
                Map.of("type", "TEST")
        );
        // La notification push doit être ignorée sans erreur car notificationsEnabled = false

        // 5. Réactiver les notifications push via /api/notifications/preferences
        mockMvc.perform(patch("/api/notifications/preferences")
                        .header("Authorization", "Bearer " + technicianToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("notificationsEnabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationsEnabled", is(true)));

        User reenabled = userRepository.findById(technician.getId()).orElseThrow();
        assertThat(reenabled.isNotificationsEnabled()).isTrue();
    }
}
