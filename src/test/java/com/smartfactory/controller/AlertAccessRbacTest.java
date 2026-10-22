package com.smartfactory.controller;

import com.smartfactory.entity.Alert;
import com.smartfactory.entity.Machine;
import com.smartfactory.entity.MachineStatus;
import com.smartfactory.entity.SensorType;
import com.smartfactory.entity.User;
import com.smartfactory.repository.AlertRepository;
import com.smartfactory.repository.MachineRepository;
import com.smartfactory.repository.UserRepository;
import com.smartfactory.security.JwtService;
import com.smartfactory.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AlertAccessRbacTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MachineRepository machineRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User admin;
    private User respIndus;
    private User tech;
    private User operator;
    private Alert testAlert;

    private String adminToken;
    private String respIndusToken;
    private String techToken;
    private String operatorToken;

    @BeforeEach
    void setUp() {
        alertRepository.deleteAll();
        userRepository.deleteAll();
        machineRepository.deleteAll();

        Machine machine = machineRepository.save(new Machine("Fraiseuse A", "MACH-01", "Fraiseuse", "zone-1", MachineStatus.RUNNING, "Desc", List.of()));

        admin = userRepository.save(new User("Admin", "Root", "admin@smartfactory.com", passwordEncoder.encode("pass"), Role.ADMIN, "ACTIVE"));
        respIndus = userRepository.save(new User("Claire", "Resp", "resp@smartfactory.com", passwordEncoder.encode("pass"), Role.RESPONSABLE_INDUSTRIEL, "ACTIVE"));
        tech = userRepository.save(new User("Jean", "Tech", "tech@smartfactory.com", passwordEncoder.encode("pass"), Role.TECHNICIAN, "ACTIVE"));
        operator = userRepository.save(new User("Paul", "Operator", "operator@smartfactory.com", passwordEncoder.encode("pass"), Role.OPERATOR, "ACTIVE"));

        adminToken = jwtService.generateToken(admin);
        respIndusToken = jwtService.generateToken(respIndus);
        techToken = jwtService.generateToken(tech);
        operatorToken = jwtService.generateToken(operator);

        testAlert = alertRepository.save(new Alert(
                machine.getId(), machine.getCode(), "sensor-1", SensorType.TEMPERATURE,
                "anom-1", "WARNING", 65.0, 60.0, "Dépassement température", java.time.Instant.now()
        ));
    }

    @Test
    @DisplayName("Section 10.7: Opérateur n'a pas accès aux alertes (403 Forbidden sur /api/alerts et /api/alerts/{id})")
    void testOperatorForbiddenFromAlerts() throws Exception {
        // Liste des alertes
        mockMvc.perform(get("/api/alerts")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isForbidden());

        // Détail d'une alerte
        mockMvc.perform(get("/api/alerts/" + testAlert.getId())
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isForbidden());

        // Synthèse des alertes
        mockMvc.perform(get("/api/alerts/summary")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Section 10.7: Technicien, Responsable Industriel et Admin ont accès aux alertes (200 OK)")
    void testAuthorizedRolesCanAccessAlerts() throws Exception {
        // 1. TECHNICIEN -> 200 OK
        mockMvc.perform(get("/api/alerts")
                        .header("Authorization", "Bearer " + techToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].severity").value("WARNING"));

        mockMvc.perform(get("/api/alerts/" + testAlert.getId())
                        .header("Authorization", "Bearer " + techToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(testAlert.getId()));

        // 2. RESPONSABLE_INDUSTRIEL -> 200 OK
        mockMvc.perform(get("/api/alerts")
                        .header("Authorization", "Bearer " + respIndusToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/alerts/" + testAlert.getId())
                        .header("Authorization", "Bearer " + respIndusToken))
                .andExpect(status().isOk());

        // 3. ADMIN -> 200 OK
        mockMvc.perform(get("/api/alerts")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/alerts/" + testAlert.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }
}
