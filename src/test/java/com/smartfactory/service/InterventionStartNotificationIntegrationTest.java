package com.smartfactory.service;

import com.smartfactory.entity.*;
import com.smartfactory.repository.*;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class InterventionStartNotificationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MachineRepository machineRepository;

    @Autowired
    private MaintenanceInterventionRepository interventionRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private MaintenanceInterventionService interventionService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private User respIndus;
    private User technician;
    private Machine machine;
    private String techToken;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        interventionRepository.deleteAll();
        machineRepository.deleteAll();
        userRepository.deleteAll();

        respIndus = userRepository.save(new User("Claire", "Resp", "resp@smartfactory.com", passwordEncoder.encode("pass123"), Role.RESPONSABLE_INDUSTRIEL, "ACTIVE"));
        technician = userRepository.save(new User("Jean", "Tech", "tech@smartfactory.com", passwordEncoder.encode("pass123"), Role.TECHNICIAN, "ACTIVE"));
        techToken = jwtService.generateToken(technician);

        machine = machineRepository.save(new Machine("Presse P-10", "PRS-10", "Presse", "zone-1", MachineStatus.RUNNING, "Presse principale", List.of()));
    }

    @Test
    @DisplayName("US44 / Exigence: Le démarrage d'une intervention notifie le responsable industriel")
    void testStartInterventionNotifiesResponsibleIndustrial() throws Exception {
        // 1. Créer une intervention corrective assignée au technicien
        MaintenanceIntervention inter = new MaintenanceIntervention(
                machine.getId(), machine.getCode(), "Dépannage hydraulique", "Perte de pression",
                MaintenanceType.CORRECTIVE, null, InterventionPriority.HIGH
        );
        inter.setTechnicianId(technician.getId());
        inter.setCreatedBy(respIndus.getId());
        inter = interventionRepository.save(inter);

        // Vider les notifications de création/assignation
        notificationRepository.deleteAll();

        // 2. Le technicien démarre l'intervention via l'API REST POST /api/maintenance/{id}/start
        mockMvc.perform(post("/api/maintenance/" + inter.getId() + "/start")
                        .header("Authorization", "Bearer " + techToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        // 3. Vérifier que le responsable industriel a reçu la notification de démarrage
        List<AppNotification> managerNotifs = notificationRepository.findByUserId(respIndus.getId());
        assertThat(managerNotifs).isNotEmpty();
        assertThat(managerNotifs.stream().anyMatch(n ->
                n.getTitle().contains("démarrée") ||
                n.getMessage().contains("démarré") ||
                "INTERVENTION_STARTED".equalsIgnoreCase(n.getType())
        )).isTrue();

        // 4. Vérifier que la machine a basculé en statut MAINTENANCE
        Machine updatedMachine = machineRepository.findById(machine.getId()).orElseThrow();
        assertThat(updatedMachine.getStatus()).isEqualTo(MachineStatus.MAINTENANCE);
    }
}
