package com.smartfactory.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfactory.dto.*;
import com.smartfactory.entity.*;
import com.smartfactory.repository.MachineRepository;
import com.smartfactory.repository.MaintenanceInterventionRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MaintenanceRbacSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MachineRepository machineRepository;

    @Autowired
    private MaintenanceInterventionRepository interventionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    private User admin;
    private User respIndus;
    private User tech1;
    private User tech2;
    private User operator;
    private Machine machine;

    private String adminToken;
    private String respIndusToken;
    private String tech1Token;
    private String tech2Token;
    private String operatorToken;

    @BeforeEach
    void setUp() {
        interventionRepository.deleteAll();
        userRepository.deleteAll();
        machineRepository.deleteAll();

        machine = machineRepository.save(new Machine("Fraiseuse CNC", "CNC-01", "Fraiseuse", "zone-1", MachineStatus.RUNNING, "Desc", List.of()));

        admin = userRepository.save(new User("Admin", "User", "admin@smartfactory.com", passwordEncoder.encode("pass"), Role.ADMIN, "ACTIVE"));
        respIndus = userRepository.save(new User("Claire", "Resp", "resp@smartfactory.com", passwordEncoder.encode("pass"), Role.RESPONSABLE_INDUSTRIEL, "ACTIVE"));
        tech1 = userRepository.save(new User("Jean", "Tech1", "tech1@smartfactory.com", passwordEncoder.encode("pass"), Role.TECHNICIAN, "ACTIVE"));
        tech2 = userRepository.save(new User("Marc", "Tech2", "tech2@smartfactory.com", passwordEncoder.encode("pass"), Role.TECHNICIAN, "ACTIVE"));
        operator = userRepository.save(new User("Paul", "Operator", "operator@smartfactory.com", passwordEncoder.encode("pass"), Role.OPERATOR, "ACTIVE"));

        adminToken = jwtService.generateToken(admin);
        respIndusToken = jwtService.generateToken(respIndus);
        tech1Token = jwtService.generateToken(tech1);
        tech2Token = jwtService.generateToken(tech2);
        operatorToken = jwtService.generateToken(operator);
    }

    @Test
    @DisplayName("Section 10.6: Création d'intervention - Autorisé pour RI, refusé (403) pour TECHNICIEN et OPERATEUR")
    void testInterventionCreationRbac() throws Exception {
        InterventionCreateRequest request = new InterventionCreateRequest();
        request.setMachineId(machine.getId());
        request.setTitle("Inspection générale");
        request.setDescription("Maintenance préventive systématique");
        request.setType(MaintenanceType.PREVENTIVE);
        request.setPreventiveSubtype(PreventiveSubtype.SYSTEMATIC);
        request.setPriority(InterventionPriority.MEDIUM);

        // 1. TECHNICIEN tente de créer -> 403 Forbidden
        mockMvc.perform(post("/api/maintenance")
                        .header("Authorization", "Bearer " + tech1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        // 2. OPERATEUR tente de créer -> 403 Forbidden
        mockMvc.perform(post("/api/maintenance")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        // 3. RESPONSABLE_INDUSTRIEL crée avec succès -> 201 Created
        mockMvc.perform(post("/api/maintenance")
                        .header("Authorization", "Bearer " + respIndusToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Inspection générale"));
    }

    @Test
    @DisplayName("Section 10.6: Modification des paramètres et assignation - Réservé à RI, refusé (403) aux TECHNICIENS")
    void testInterventionManagementRbac() throws Exception {
        // Créer une intervention via le repo
        MaintenanceIntervention inter = new MaintenanceIntervention(
                machine.getId(), machine.getCode(), "Changement roulement", "Desc",
                MaintenanceType.CORRECTIVE, null, InterventionPriority.HIGH
        );
        inter.setTechnicianId(tech1.getId());
        inter.setTechnicianName(tech1.getFirstName() + " " + tech1.getLastName());
        inter = interventionRepository.save(inter);

        // 1. Tech 1 tente de modifier les paramètres généraux (PUT /api/maintenance/{id}) -> 403 Forbidden
        InterventionUpdateRequest updateReq = new InterventionUpdateRequest();
        updateReq.setTitle("Nouveau titre non autorisé");
        updateReq.setPriority(InterventionPriority.CRITICAL);

        mockMvc.perform(put("/api/maintenance/" + inter.getId())
                        .header("Authorization", "Bearer " + tech1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isForbidden());

        // 2. Tech 1 tente de réassigner l'intervention à Tech 2 -> 403 Forbidden
        InterventionAssignRequest assignReq = new InterventionAssignRequest();
        assignReq.setTechnicianId(tech2.getId());

        mockMvc.perform(put("/api/maintenance/" + inter.getId() + "/assign")
                        .header("Authorization", "Bearer " + tech1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(assignReq)))
                .andExpect(status().isForbidden());

        // 3. RESPONSABLE_INDUSTRIEL modifie les paramètres avec succès
        mockMvc.perform(put("/api/maintenance/" + inter.getId())
                        .header("Authorization", "Bearer " + respIndusToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Nouveau titre non autorisé"))
                .andExpect(jsonPath("$.priority").value("CRITICAL"));

        // 4. RESPONSABLE_INDUSTRIEL réassigne à Tech 2 avec succès
        mockMvc.perform(put("/api/maintenance/" + inter.getId() + "/assign")
                        .header("Authorization", "Bearer " + respIndusToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(assignReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.technicianId").value(tech2.getId()));
    }

    @Test
    @DisplayName("Section 10.6: Exécution d'intervention - Tech assigné OK, Tech non-assigné et Opérateur 403")
    void testInterventionExecutionPermissions() throws Exception {
        // Intervention assignée à Tech 1
        MaintenanceIntervention inter = new MaintenanceIntervention(
                machine.getId(), machine.getCode(), "Dépannage variateur", "Desc",
                MaintenanceType.CORRECTIVE, null, InterventionPriority.HIGH
        );
        inter.setTechnicianId(tech1.getId());
        inter.setTechnicianName(tech1.getFirstName() + " " + tech1.getLastName());
        inter = interventionRepository.save(inter);

        // 1. Tech 2 (non assigné) tente d'ajouter un diagnostic -> 403 Forbidden
        DiagnosisCreateRequest diagReq = new DiagnosisCreateRequest();
        diagReq.setProbableCause("Surchauffe IGBT");
        diagReq.setDetails("Détails test");

        mockMvc.perform(post("/api/maintenance/" + inter.getId() + "/diagnoses")
                        .header("Authorization", "Bearer " + tech2Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(diagReq)))
                .andExpect(status().isForbidden());

        // 2. Opérateur tente d'ajouter une action -> 403 Forbidden
        ActionCreateRequest actReq = new ActionCreateRequest();
        actReq.setDescription("Remplacement fusible");

        mockMvc.perform(post("/api/maintenance/" + inter.getId() + "/actions")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(actReq)))
                .andExpect(status().isForbidden());

        // 3. Tech 1 (assigné) ajoute un diagnostic avec succès -> 200 OK
        mockMvc.perform(post("/api/maintenance/" + inter.getId() + "/diagnoses")
                        .header("Authorization", "Bearer " + tech1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(diagReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnoses[0].probableCause").value("Surchauffe IGBT"));

        // 4. Tech 1 (assigné) ajoute une action avec succès -> 200 OK
        mockMvc.perform(post("/api/maintenance/" + inter.getId() + "/actions")
                        .header("Authorization", "Bearer " + tech1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(actReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[0].description").value("Remplacement fusible"));

        // 5. Tech 1 clôture l'intervention avec succès -> 200 OK
        InterventionCloseRequest closeReq = new InterventionCloseRequest();
        closeReq.setClosureNotes("Intervention terminée avec succès");
        closeReq.setRestoreMachineStatus(true);

        mockMvc.perform(put("/api/maintenance/" + inter.getId() + "/close")
                        .header("Authorization", "Bearer " + tech1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(closeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("Section 10.6: Consultation - Tech ne voit que ses interventions, Opérateur 403")
    void testInterventionConsultationPermissions() throws Exception {
        // Inter 1 assignée à Tech 1
        MaintenanceIntervention inter1 = new MaintenanceIntervention(
                machine.getId(), machine.getCode(), "Intervention Tech 1", "Desc 1",
                MaintenanceType.CORRECTIVE, null, InterventionPriority.HIGH
        );
        inter1.setTechnicianId(tech1.getId());
        inter1 = interventionRepository.save(inter1);

        // Inter 2 assignée à Tech 2
        MaintenanceIntervention inter2 = new MaintenanceIntervention(
                machine.getId(), machine.getCode(), "Intervention Tech 2", "Desc 2",
                MaintenanceType.CORRECTIVE, null, InterventionPriority.MEDIUM
        );
        inter2.setTechnicianId(tech2.getId());
        inter2 = interventionRepository.save(inter2);

        // 1. OPERATEUR tente de consulter la liste -> 403 Forbidden
        mockMvc.perform(get("/api/maintenance")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isForbidden());

        // 2. OPERATEUR tente de consulter le détail -> 403 Forbidden
        mockMvc.perform(get("/api/maintenance/" + inter1.getId())
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isForbidden());

        // 3. Tech 1 consulte le détail d'inter 2 (qui ne lui est pas assignée) -> 403 Forbidden
        mockMvc.perform(get("/api/maintenance/" + inter2.getId())
                        .header("Authorization", "Bearer " + tech1Token))
                .andExpect(status().isForbidden());

        // 4. Tech 1 consulte le détail de sa propre intervention -> 200 OK
        mockMvc.perform(get("/api/maintenance/" + inter1.getId())
                        .header("Authorization", "Bearer " + tech1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Intervention Tech 1"));

        // 5. Tech 1 consulte la liste : la requête est filtrée sur ses seules interventions
        mockMvc.perform(get("/api/maintenance")
                        .header("Authorization", "Bearer " + tech1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Intervention Tech 1"));
    }
}
