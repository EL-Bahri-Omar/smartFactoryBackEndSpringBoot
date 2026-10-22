package com.smartfactory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfactory.dto.InterventionAssignRequest;
import com.smartfactory.dto.InterventionCreateRequest;
import com.smartfactory.entity.*;
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
        InterventionCreateRequest createReq = new InterventionCreateRequest();
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
}
