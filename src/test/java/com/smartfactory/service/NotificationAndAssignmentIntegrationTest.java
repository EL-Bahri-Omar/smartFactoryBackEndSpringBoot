package com.smartfactory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfactory.entity.*;
import com.smartfactory.repository.DeviceTokenRepository;
import com.smartfactory.repository.MachineRepository;
import com.smartfactory.repository.MaintenanceInterventionRepository;
import com.smartfactory.repository.NotificationRepository;
import com.smartfactory.repository.UserRepository;
import com.smartfactory.security.JwtService;
import com.smartfactory.security.Role;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class NotificationAndAssignmentIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DeviceTokenRepository deviceTokenRepository;

    @Autowired
    private MachineRepository machineRepository;

    @Autowired
    private MaintenanceInterventionRepository interventionRepository;

    @Autowired
    private MaintenanceInterventionService interventionService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private User tech1;
    private User tech2;
    private User respIndus;
    private Machine machine;

    private String tech1Token;
    private String tech2Token;
    private String respIndusToken;

    @BeforeEach
    void setUp() {
        deviceTokenRepository.deleteAll();
        notificationRepository.deleteAll();
        interventionRepository.deleteAll();
        userRepository.deleteAll();
        machineRepository.deleteAll();

        machine = machineRepository.save(new Machine("Presse Hydraulique", "PRS-001", "Presse", "zone-1", MachineStatus.RUNNING, "Desc", List.of()));

        tech1 = userRepository.save(new User("Jean", "Tech", "tech1@smartfactory.com", passwordEncoder.encode("pass"), Role.TECHNICIAN, "ACTIVE"));
        tech2 = userRepository.save(new User("Marc", "Tech", "tech2@smartfactory.com", passwordEncoder.encode("pass"), Role.TECHNICIAN, "ACTIVE"));
        respIndus = userRepository.save(new User("Claire", "Resp", "resp@smartfactory.com", passwordEncoder.encode("pass"), Role.RESPONSABLE_INDUSTRIEL, "ACTIVE"));

        tech1Token = jwtService.generateToken(tech1);
        tech2Token = jwtService.generateToken(tech2);
        respIndusToken = jwtService.generateToken(respIndus);
    }

    @Test
    @DisplayName("Section 10.4: Assignation et réassignation déclenchent des notifications ciblées et isolées")
    void testAssignmentAndReassignmentNotifications() {
        // 1. Création avec assignation initiale à tech1
        MaintenanceIntervention createReq = new MaintenanceIntervention();
        createReq.setMachineId(machine.getId());
        createReq.setTitle("Maintenance initiale");
        createReq.setDescription("Vérification circuit hydraulique");
        createReq.setType(MaintenanceType.CORRECTIVE);
        createReq.setPriority(InterventionPriority.HIGH);
        createReq.setTechnicianId(tech1.getId());

        MaintenanceIntervention created = interventionService.createIntervention(createReq, respIndus.getEmail());

        // Vérifier que tech1 a reçu exactement 1 notification d'assignation
        List<AppNotification> tech1Notifs = notificationRepository.findByUserId(tech1.getId());
        List<AppNotification> tech2Notifs = notificationRepository.findByUserId(tech2.getId());

        assertThat(tech1Notifs).hasSize(1);
        assertThat(tech1Notifs.get(0).getTitle().toLowerCase()).contains("assign");
        assertThat(tech1Notifs.get(0).getMessage()).contains("PRS-001");
        assertThat(tech1Notifs.get(0).isRead()).isFalse();

        // tech2 ne doit rien recevoir
        assertThat(tech2Notifs).isEmpty();

        // 2. Réassignation de tech1 vers tech2
        interventionService.assignTechnician(created.getId(), tech2.getId(), respIndus.getEmail());

        tech1Notifs = notificationRepository.findByUserId(tech1.getId());
        tech2Notifs = notificationRepository.findByUserId(tech2.getId());

        // tech1 reçoit la notification de réassignation / désassignation
        assertThat(tech1Notifs).hasSize(2);
        assertThat(tech1Notifs.stream().anyMatch(n -> n.getTitle().toLowerCase().contains("assign"))).isTrue();

        // tech2 reçoit la notification d'assignation
        assertThat(tech2Notifs).hasSize(1);
        assertThat(tech2Notifs.get(0).getTitle().toLowerCase()).contains("assign");
    }

    @Test
    @DisplayName("Section 10.4: Isolation stricte des notifications via l'API REST")
    void testNotificationApiUserIsolation() throws Exception {
        // Notif pour tech1
        AppNotification notif1 = new AppNotification(
                tech1.getId(), null, machine.getId(), "Alerte Tech 1", "Message 1", "MAINTENANCE"
        );
        notif1.setRead(false);
        notificationRepository.save(notif1);

        // Notif pour tech2
        AppNotification notif2 = new AppNotification(
                tech2.getId(), null, machine.getId(), "Alerte Tech 2", "Message 2", "MAINTENANCE"
        );
        notif2.setRead(false);
        notificationRepository.save(notif2);

        // Tech 1 consulte ses notifications : il ne voit QUE notif1
        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + tech1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].title").value("Alerte Tech 1"));

        // Tech 2 consulte ses notifications : il ne voit QUE notif2
        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + tech2Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].title").value("Alerte Tech 2"));
    }

    @Test
    @DisplayName("Section 10.5: Marquer comme lue - persistance et sécurité inter-utilisateurs")
    void testMarkAsReadPersistenceAndForbiddenAccess() throws Exception {
        AppNotification notif1 = new AppNotification(
                tech1.getId(), null, machine.getId(), "Notification Test", "Message non lu", "MAINTENANCE"
        );
        notif1.setRead(false);
        notif1 = notificationRepository.save(notif1);

        // 1. Tech 2 tente de marquer comme lue la notification de Tech 1 -> 403 Forbidden
        mockMvc.perform(put("/api/notifications/" + notif1.getId() + "/read")
                        .header("Authorization", "Bearer " + tech2Token))
                .andExpect(status().isForbidden());

        // Vérifier qu'elle est toujours non lue
        AppNotification fresh = notificationRepository.findById(notif1.getId()).orElseThrow();
        assertThat(fresh.isRead()).isFalse();

        // 2. Tech 1 marque sa notification comme lue via PUT /api/notifications/{id}/read
        mockMvc.perform(put("/api/notifications/" + notif1.getId() + "/read")
                        .header("Authorization", "Bearer " + tech1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true))
                .andExpect(jsonPath("$.isRead").value(true));

        // 3. Persistance après rechargement
        fresh = notificationRepository.findById(notif1.getId()).orElseThrow();
        assertThat(fresh.isRead()).isTrue();

        // 4. Compteur non lues est désormais à 0 pour Tech 1
        mockMvc.perform(get("/api/notifications/unread-count")
                        .header("Authorization", "Bearer " + tech1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    @DisplayName("Multiple Technicians: Assignation multiple via API et notifications isolées")
    void testMultipleTechniciansAssignmentNotifications() throws Exception {
        MaintenanceIntervention createReq = new MaintenanceIntervention();
        createReq.setMachineId(machine.getId());
        createReq.setTitle("Maintenance groupe");
        createReq.setDescription("Intervention multi-techniciens");
        createReq.setType(MaintenanceType.CORRECTIVE);
        createReq.setPriority(InterventionPriority.HIGH);

        MaintenanceIntervention created = interventionService.createIntervention(createReq, respIndus.getEmail());

        MaintenanceIntervention assignReq = new MaintenanceIntervention();
        assignReq.setTechnicianIds(List.of(tech1.getId(), tech2.getId()));

        mockMvc.perform(put("/api/maintenance/" + created.getId() + "/assign")
                        .header("Authorization", "Bearer " + respIndusToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(assignReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.technicianIds", hasSize(2)))
                .andExpect(jsonPath("$.status").value("ASSIGNED"));

        // Vérifier que tech1 et tech2 ont tous les deux reçu une notification avec interventionId
        List<AppNotification> tech1Notifs = notificationRepository.findByUserId(tech1.getId());
        List<AppNotification> tech2Notifs = notificationRepository.findByUserId(tech2.getId());

        assertThat(tech1Notifs).hasSize(1);
        assertThat(tech1Notifs.get(0).getInterventionId()).isEqualTo(created.getId());
        assertThat(tech2Notifs).hasSize(1);
        assertThat(tech2Notifs.get(0).getInterventionId()).isEqualTo(created.getId());
    }

    @Test
    @DisplayName("Creator Notifications: Les actions du technicien notifient le créateur de l'intervention")
    void testTechnicianActionsNotifyInterventionCreator() {
        MaintenanceIntervention createReq = new MaintenanceIntervention();
        createReq.setMachineId(machine.getId());
        createReq.setTitle("Vérification moteur");
        createReq.setDescription("Diagnostic et réparation");
        createReq.setType(MaintenanceType.CORRECTIVE);
        createReq.setPriority(InterventionPriority.MEDIUM);
        createReq.setTechnicianId(tech1.getId());

        MaintenanceIntervention interv = interventionService.createIntervention(createReq, respIndus.getEmail());
        notificationRepository.deleteAll(); // Vider les notifications initiales d'assignation

        // 1. Technicien ajoute un diagnostic
        interventionService.addDiagnosis(interv.getId(), new MaintenanceDiagnosis("Roulement usé", "Niveau sonore anormal", tech1.getId(), "Jean Tech"), tech1.getId(), "Jean Tech");
        List<AppNotification> creatorNotifs = notificationRepository.findByUserId(respIndus.getId());
        assertThat(creatorNotifs).hasSize(1);
        assertThat(creatorNotifs.get(0).getMessage()).contains("Diagnostic");
        assertThat(creatorNotifs.get(0).getInterventionId()).isEqualTo(interv.getId());

        // 2. Technicien ajoute une action
        interventionService.addAction(interv.getId(), new MaintenanceAction("Graissage", tech1.getId(), "Jean Tech", "Graisse haute performance", 20), tech1.getId(), "Jean Tech");
        creatorNotifs = notificationRepository.findByUserId(respIndus.getId());
        assertThat(creatorNotifs).hasSize(2);
        assertThat(creatorNotifs.stream().anyMatch(n -> n.getTitle().contains("Action") || n.getMessage().contains("Graissage"))).isTrue();

        // 3. Technicien ajoute une observation
        interventionService.addObservation(interv.getId(), new MaintenanceObservation("Surveiller vibration", tech1.getId(), "Jean Tech", "OPERATING_CONDITION"), tech1.getId(), "Jean Tech");
        creatorNotifs = notificationRepository.findByUserId(respIndus.getId());
        assertThat(creatorNotifs).hasSize(3);
        assertThat(creatorNotifs.stream().anyMatch(n -> n.getTitle().contains("Observation") || n.getMessage().contains("vibration"))).isTrue();

        // 4. Technicien clôture l'intervention
        MaintenanceIntervention closeReq = new MaintenanceIntervention();
        closeReq.setClosureNotes("Réparation terminée avec succès");
        closeReq.setRestoreMachineStatus(true);
        interventionService.closeIntervention(interv.getId(), closeReq, "Jean Tech");
        creatorNotifs = notificationRepository.findByUserId(respIndus.getId());
        assertThat(creatorNotifs).hasSize(4);
        assertThat(creatorNotifs.stream().anyMatch(n -> n.getTitle().contains("terminée"))).isTrue();
    }

    @Test
    @DisplayName("Device Token: Enregistrement et désactivation (suppression logique)")
    void testDeviceTokenRegistrationAndDeactivation() throws Exception {
        DeviceToken registerReq = new DeviceToken(tech1.getId(), "fcm-tok-123456", "ANDROID", "device-galaxy-s24");

        // 1. Enregistrement du token (HTTP 201 Created)
        mockMvc.perform(post("/api/notifications/tokens")
                        .header("Authorization", "Bearer " + tech1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerReq)))
                .andExpect(status().isCreated());

        List<DeviceToken> tokens = deviceTokenRepository.findByUserId(tech1.getId());
        assertThat(tokens).hasSize(1);
        assertThat(tokens.get(0).getToken()).isEqualTo("fcm-tok-123456");
        assertThat(tokens.get(0).getDeviceId()).isEqualTo("device-galaxy-s24");
        assertThat(tokens.get(0).isActive()).isTrue();

        // 2. Désactivation du token via DELETE /api/notifications/tokens
        java.util.Map<String, String> deleteReq = java.util.Map.of("token", "fcm-tok-123456");
        mockMvc.perform(delete("/api/notifications/tokens")
                        .header("Authorization", "Bearer " + tech1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteReq)))
                .andExpect(status().isNoContent());

        tokens = deviceTokenRepository.findByUserId(tech1.getId());
        assertThat(tokens).hasSize(1);
        assertThat(tokens.get(0).isActive()).isFalse();

        // 3. La requête des tokens actifs ne renvoie plus ce token
        List<DeviceToken> activeTokens = deviceTokenRepository.findByUserIdAndActiveTrue(tech1.getId());
        assertThat(activeTokens).isEmpty();
    }
}
