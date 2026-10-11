package com.smartfactory.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfactory.dto.UpdatePermissionsRequest;
import com.smartfactory.entity.User;
import com.smartfactory.repository.UserRepository;
import com.smartfactory.security.JwtService;
import com.smartfactory.security.Permission;
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

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UserPermissionsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    private User admin;
    private User operatorA;
    private User operatorB;

    private String adminToken;
    private String operatorAToken;
    private String operatorBToken;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();

        // Admin
        admin = new User("Admin", "Root", "admin@smartfactory.com", passwordEncoder.encode("adminpass"), Role.ADMIN, "ACTIVE");
        admin = userRepository.save(admin);
        adminToken = jwtService.generateToken(admin);

        // Opérateur A : Rôle OPERATOR standard (consultation uniquement)
        operatorA = new User("Operateur", "Alpha", "operatorA@smartfactory.com", passwordEncoder.encode("opPass1"), Role.OPERATOR, "ACTIVE");
        operatorA = userRepository.save(operatorA);
        operatorAToken = jwtService.generateToken(operatorA);

        // Opérateur B : Même rôle OPERATOR, mais privilèges personnalisés accordés (THRESHOLD_WRITE, MACHINE_WRITE)
        operatorB = new User("Operateur", "Beta", "operatorB@smartfactory.com", passwordEncoder.encode("opPass2"), Role.OPERATOR, "ACTIVE");
        operatorB.setCustomGrantedPermissions(Set.of(Permission.THRESHOLD_WRITE, Permission.MACHINE_WRITE));
        operatorB = userRepository.save(operatorB);
        operatorBToken = jwtService.generateToken(operatorB);
    }

    @Test
    @DisplayName("Deux utilisateurs ayant le même rôle ont des privilèges effectifs différents")
    void testTwoUsersSameRoleHaveDifferentEffectivePermissions() {
        User freshA = userRepository.findById(operatorA.getId()).orElseThrow();
        User freshB = userRepository.findById(operatorB.getId()).orElseThrow();

        assertThat(freshA.getRole()).isEqualTo(Role.OPERATOR);
        assertThat(freshB.getRole()).isEqualTo(Role.OPERATOR);

        // Opérateur A n'a pas MACHINE_WRITE ni THRESHOLD_WRITE
        assertThat(freshA.hasEffectivePermission(Permission.MACHINE_WRITE)).isFalse();
        assertThat(freshA.hasEffectivePermission(Permission.THRESHOLD_WRITE)).isFalse();
        assertThat(freshA.hasEffectivePermission(Permission.MACHINE_READ)).isTrue();

        // Opérateur B possède MACHINE_WRITE et THRESHOLD_WRITE personnalisés
        assertThat(freshB.hasEffectivePermission(Permission.MACHINE_WRITE)).isTrue();
        assertThat(freshB.hasEffectivePermission(Permission.THRESHOLD_WRITE)).isTrue();
        assertThat(freshB.hasEffectivePermission(Permission.MACHINE_READ)).isTrue();
    }

    @Test
    @DisplayName("Opérateur A sans privilège d'écriture est bloqué en 403 Forbidden sur la création machine")
    void testOperatorAForbiddenToWriteMachine() throws Exception {
        String machineJson = """
                {
                  "name": "Tour Fraiseur A",
                  "code": "TOUR-001",
                  "type": "Milling",
                  "status": "RUNNING"
                }
                """;

        mockMvc.perform(post("/api/machines")
                        .header("Authorization", "Bearer " + operatorAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(machineJson))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Opérateur B avec MACHINE_WRITE peut créer une machine avec succès")
    void testOperatorBAllowedToWriteMachine() throws Exception {
        String machineJson = """
                {
                  "name": "Tour Fraiseur B",
                  "code": "TOUR-002",
                  "type": "Milling",
                  "status": "RUNNING"
                }
                """;

        mockMvc.perform(post("/api/machines")
                        .header("Authorization", "Bearer " + operatorBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(machineJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("TOUR-002"));
    }

    @Test
    @DisplayName("Révocation explicite d'une permission par défaut du rôle (Refus prioritaire)")
    void testRevokePermissionOverridesRoleDefault() {
        // OPERATOR a MACHINE_READ par défaut. On lui révoque explicitement.
        operatorA.setCustomRevokedPermissions(Set.of(Permission.MACHINE_READ));
        User savedA = userRepository.save(operatorA);

        assertThat(savedA.hasEffectivePermission(Permission.MACHINE_READ)).isFalse();
        assertThat(savedA.getEffectivePermissions()).doesNotContain(Permission.MACHINE_READ);
    }

    @Test
    @DisplayName("Attribution et modification dynamique des permissions via API PUT /api/users/{id}/permissions")
    void testUpdateUserPermissionsViaApi() throws Exception {
        // Admin accorde à Opérateur A la checklist souhaitée
        UpdatePermissionsRequest request = new UpdatePermissionsRequest();
        request.setPermissions(Set.of(
                Permission.MACHINE_READ,
                Permission.READINGS_READ,
                Permission.ALERT_READ,
                Permission.ALERT_ACKNOWLEDGE // nouveau privilège
        ));

        mockMvc.perform(put("/api/users/" + operatorA.getId() + "/permissions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectivePermissions", hasItem("ALERT_ACKNOWLEDGE")));

        User reloadedA = userRepository.findById(operatorA.getId()).orElseThrow();
        assertThat(reloadedA.hasEffectivePermission(Permission.ALERT_ACKNOWLEDGE)).isTrue();
    }

    @Test
    @DisplayName("Un utilisateur standard ne peut pas modifier ses propres privilèges (403 Forbidden)")
    void testOrdinaryUserCannotEscalatePermissions() throws Exception {
        UpdatePermissionsRequest request = new UpdatePermissionsRequest();
        request.setPermissions(Set.of(Permission.USER_WRITE, Permission.USER_PERMISSIONS_MANAGE));

        mockMvc.perform(put("/api/users/" + operatorA.getId() + "/permissions")
                        .header("Authorization", "Bearer " + operatorAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Consultation du catalogue de permissions /api/users/permissions/catalog")
    void testGetPermissionsCatalog() throws Exception {
        mockMvc.perform(get("/api/users/permissions/catalog")
                        .header("Authorization", "Bearer " + operatorAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", hasSize(greaterThanOrEqualTo(10))))
                .andExpect(jsonPath("$.roles.OPERATOR").exists())
                .andExpect(jsonPath("$.roles.ADMIN").exists());
    }
}
