package com.smartfactory.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfactory.entity.*;
import com.smartfactory.repository.AlertRepository;
import com.smartfactory.repository.MachineRepository;
import com.smartfactory.repository.MachineThresholdRepository;
import com.smartfactory.repository.UserRepository;
import com.smartfactory.security.JwtService;
import com.smartfactory.security.Permission;
import com.smartfactory.security.Role;
import com.smartfactory.service.MachineThresholdService;
import com.smartfactory.service.SensorReadingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MachineThresholdIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MachineRepository machineRepository;

    @Autowired
    private MachineThresholdRepository thresholdRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MachineThresholdService thresholdService;

    @Autowired
    private SensorReadingService readingService;

    private Machine machine1;
    private Machine machine2;

    private User admin;
    private User authorizedOperator;
    private User unauthorizedOperator;

    private String adminToken;
    private String authorizedToken;
    private String unauthorizedToken;

    @BeforeEach
    void setUp() {
        alertRepository.deleteAll();
        thresholdRepository.deleteAll();
        machineRepository.deleteAll();
        userRepository.deleteAll();
        thresholdService.clearCache();

        // Création des machines
        machine1 = machineRepository.save(new Machine("Fraiseuse CNC 1", "CNC-001", "CNC", null, MachineStatus.RUNNING, "Desc 1", null));
        machine2 = machineRepository.save(new Machine("Presse P2", "PRS-002", "Press", null, MachineStatus.RUNNING, "Desc 2", null));

        // Utilisateurs
        admin = userRepository.save(new User("Admin", "Root", "admin@smartfactory.com", passwordEncoder.encode("pass"), Role.ADMIN, "ACTIVE"));
        adminToken = jwtService.generateToken(admin);

        authorizedOperator = new User("Op", "Authorized", "op.auth@smartfactory.com", passwordEncoder.encode("pass"), Role.OPERATOR, "ACTIVE");
        authorizedOperator.setCustomGrantedPermissions(Set.of(Permission.THRESHOLD_READ, Permission.THRESHOLD_WRITE));
        authorizedOperator = userRepository.save(authorizedOperator);
        authorizedToken = jwtService.generateToken(authorizedOperator);

        unauthorizedOperator = new User("Op", "Unauthorized", "op.unauth@smartfactory.com", passwordEncoder.encode("pass"), Role.OPERATOR, "ACTIVE");
        unauthorizedOperator = userRepository.save(unauthorizedOperator);
        unauthorizedToken = jwtService.generateToken(unauthorizedOperator);
    }

    @Test
    @DisplayName("Sauvegarde et persistance des seuils d'une machine dans la base de données")
    void testSaveAndPersistThreshold() throws Exception {
        MachineThreshold threshold = new MachineThreshold(
                machine1.getId(),
                machine1.getCode(),
                SensorType.TEMPERATURE,
                55.0, 75.0,
                null, 95.0,
                "°C", true, "Seuil personnalisé machine 1"
        );

        mockMvc.perform(post("/api/machines/" + machine1.getId() + "/thresholds")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(threshold)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warningMax").value(75.0))
                .andExpect(jsonPath("$.criticalMax").value(95.0));

        // Vérification en base de données
        MachineThreshold saved = thresholdRepository.findByMachineIdAndSensorType(machine1.getId(), SensorType.TEMPERATURE).orElseThrow();
        assertThat(saved.getWarningMax()).isEqualTo(75.0);
        assertThat(saved.getCriticalMax()).isEqualTo(95.0);
    }

    @Test
    @DisplayName("L'utilisateur non autorisé ne peut pas modifier les seuils (403 Forbidden)")
    void testUnauthorizedUserCannotModifyThreshold() throws Exception {
        MachineThreshold threshold = new MachineThreshold(
                machine1.getId(), machine1.getCode(), SensorType.TEMPERATURE,
                50.0, 70.0, null, 90.0, "°C", true, "Tentative"
        );

        mockMvc.perform(post("/api/machines/" + machine1.getId() + "/thresholds")
                        .header("Authorization", "Bearer " + unauthorizedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(threshold)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("L'opérateur autorisé avec THRESHOLD_WRITE peut modifier les seuils avec succès")
    void testAuthorizedOperatorCanModifyThreshold() throws Exception {
        MachineThreshold threshold = new MachineThreshold(
                machine1.getId(), machine1.getCode(), SensorType.TEMPERATURE,
                50.0, 72.0, null, 92.0, "°C", true, "Mise à jour par opérateur autorisé"
        );

        mockMvc.perform(post("/api/machines/" + machine1.getId() + "/thresholds")
                        .header("Authorization", "Bearer " + authorizedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(threshold)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warningMax").value(72.0));
    }

    @Test
    @DisplayName("Rejet des configurations de seuils incohérentes ou invalides")
    void testRejectIncoherentThresholds() throws Exception {
        // warningMin > warningMax
        MachineThreshold badThreshold = new MachineThreshold(
                machine1.getId(), machine1.getCode(), SensorType.TEMPERATURE,
                80.0, 50.0, null, 90.0, "°C", true, "Incoherent"
        );

        mockMvc.perform(post("/api/machines/" + machine1.getId() + "/thresholds")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badThreshold)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Prise en compte à chaud d'une modification de seuil sans redémarrage lors de la réception MQTT")
    void testHotReloadOfThresholdWithoutRestart() throws Exception {
        // 1. Définir un seuil initial : warning à 60°C, critical à 80°C
        MachineThreshold initialThreshold = new MachineThreshold(
                machine1.getId(), machine1.getCode(), SensorType.TEMPERATURE,
                null, 60.0,
                null, 80.0,
                "°C", true, "Seuil initial"
        );
        thresholdService.saveOrUpdateThreshold(machine1.getId(), initialThreshold);

        // Mesure à 65°C -> Déclenche WARNING
        String topic = "factory/machines/" + machine1.getCode() + "/temperature";
        String payload1 = String.format("""
                {
                  "machineId": "%s",
                  "sensorType": "TEMPERATURE",
                  "value": 65.0,
                  "unit": "°C",
                  "timestamp": "%s"
                }
                """, machine1.getCode(), Instant.now().toString());

        readingService.validateAndProcess(topic, payload1);

        List<Alert> alerts1 = alertRepository.findAll();
        assertThat(alerts1).hasSize(1);
        assertThat(alerts1.get(0).getSeverity()).isEqualTo("WARNING");

        // 2. À chaud via l'API REST : on relève le seuil warning à 70°C
        MachineThreshold updatedThreshold = new MachineThreshold(
                machine1.getId(), machine1.getCode(), SensorType.TEMPERATURE,
                null, 70.0,
                null, 85.0,
                "°C", true, "Seuil relevé"
        );
        mockMvc.perform(post("/api/machines/" + machine1.getId() + "/thresholds")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatedThreshold)))
                .andExpect(status().isCreated());

        // Mesure à 66°C -> Ne doit PLUS déclencher de warning car < 70°C !
        String payload2 = String.format("""
                {
                  "machineId": "%s",
                  "sensorType": "TEMPERATURE",
                  "value": 66.0,
                  "unit": "°C",
                  "timestamp": "%s"
                }
                """, machine1.getCode(), Instant.now().plusSeconds(1).toString());

        readingService.validateAndProcess(topic, payload2);

        // Le nombre d'alertes reste à 1 (pas de fausse alerte grâce à la prise en compte immédiate)
        List<Alert> alerts2 = alertRepository.findAll();
        assertThat(alerts2).hasSize(1);
    }

    @Test
    @DisplayName("Isolation stricte des seuils entre deux machines différentes")
    void testThresholdIsolationBetweenMachines() {
        // Machine 1 : warning temp à 50°C
        thresholdService.saveOrUpdateThreshold(machine1.getId(), new MachineThreshold(
                machine1.getId(), machine1.getCode(), SensorType.TEMPERATURE,
                null, 50.0, null, 70.0, "°C", true, "Machine 1"
        ));

        // Machine 2 : warning temp à 90°C
        thresholdService.saveOrUpdateThreshold(machine2.getId(), new MachineThreshold(
                machine2.getId(), machine2.getCode(), SensorType.TEMPERATURE,
                null, 90.0, null, 110.0, "°C", true, "Machine 2"
        ));

        // Évaluation à 60°C :
        // Sur Machine 1 -> Alerte WARNING (60 >= 50)
        MachineThreshold.EvaluationResult eval1 = thresholdService.evaluateReading(machine1, SensorType.TEMPERATURE, 60.0);
        assertThat(eval1.isAlertTriggered()).isTrue();
        assertThat(eval1.getSeverity()).isEqualTo("WARNING");

        // Sur Machine 2 -> Pas d'alerte (60 < 90)
        MachineThreshold.EvaluationResult eval2 = thresholdService.evaluateReading(machine2, SensorType.TEMPERATURE, 60.0);
        assertThat(eval2.isAlertTriggered()).isFalse();
    }
}
