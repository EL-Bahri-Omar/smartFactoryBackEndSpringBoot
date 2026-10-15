package com.smartfactory.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfactory.dto.RoleCreateRequest;
import com.smartfactory.dto.RoleUpdateRequest;
import com.smartfactory.entity.User;
import com.smartfactory.repository.RoleRepository;
import com.smartfactory.repository.UserRepository;
import com.smartfactory.security.JwtService;
import com.smartfactory.security.Permission;
import com.smartfactory.security.Role;
import com.smartfactory.service.RoleService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class RoleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleService roleService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private String adminToken;
    private String operatorToken;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        roleRepository.deleteAll();

        // Initialiser les rôles système
        roleService.initializeDefaultRoles();

        // Admin user
        User admin = new User("Admin", "User", "admin.role@smartfactory.com",
                passwordEncoder.encode("adminpass"), Role.ADMIN, "ACTIVE");
        admin = userRepository.save(admin);
        adminToken = jwtService.generateToken(admin);

        // Operator user (sans privilège ROLE_CREATE/ROLE_DELETE)
        User operator = new User("Operator", "User", "operator.role@smartfactory.com",
                passwordEncoder.encode("operatorpass"), Role.OPERATOR, "ACTIVE");
        operator = userRepository.save(operator);
        operatorToken = jwtService.generateToken(operator);
    }

    @Test
    @DisplayName("Part C: Admin can list all roles and privileges catalog")
    void testGetAllRolesAndPrivileges() throws Exception {
        mockMvc.perform(get("/api/roles")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(4)))
                .andExpect(jsonPath("$[?(@.name == 'ADMIN')].systemRole").value(true))
                .andExpect(jsonPath("$[?(@.name == 'OPERATOR')].systemRole").value(true));

        mockMvc.perform(get("/api/roles/privileges")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(15)))
                .andExpect(jsonPath("$[?(@.code == 'MACHINE_READ')]").exists())
                .andExpect(jsonPath("$[?(@.code == 'ROLE_CREATE')]").exists());
    }

    @Test
    @DisplayName("Part C: Admin can create a custom dynamic role with assigned privileges")
    void testCreateCustomRole() throws Exception {
        RoleCreateRequest request = new RoleCreateRequest(
                "QUALITY_AUDITOR",
                "Responsable du contrôle qualité des machines et de l'historique",
                Set.of("MACHINE_READ", "READINGS_READ", "DASHBOARD_READ")
        );

        mockMvc.perform(post("/api/roles")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("QUALITY_AUDITOR"))
                .andExpect(jsonPath("$.systemRole").value(false))
                .andExpect(jsonPath("$.privileges.length()").value(3))
                .andExpect(jsonPath("$.description").value("Responsable du contrôle qualité des machines et de l'historique"));

        assertThat(roleRepository.existsByNameIgnoreCase("QUALITY_AUDITOR")).isTrue();
    }

    @Test
    @DisplayName("Part C: Unauthorized operator without ROLE_CREATE cannot create a role (403 Forbidden)")
    void testOperatorCannotCreateRole() throws Exception {
        RoleCreateRequest request = new RoleCreateRequest(
                "FORBIDDEN_ROLE",
                "Tentative de création sans privilège",
                Set.of("MACHINE_READ")
        );

        mockMvc.perform(post("/api/roles")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Part C: Duplicate role name is rejected (409 Conflict)")
    void testDuplicateRoleNameRejected() throws Exception {
        RoleCreateRequest req1 = new RoleCreateRequest("TEST_DUPLICATE", "Desc 1", Set.of("MACHINE_READ"));
        mockMvc.perform(post("/api/roles")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        RoleCreateRequest req2 = new RoleCreateRequest("TEST_DUPLICATE", "Desc 2", Set.of("MACHINE_READ"));
        mockMvc.perform(post("/api/roles")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Part C: Admin can update role privileges dynamically")
    void testUpdateRolePrivileges() throws Exception {
        RoleCreateRequest createReq = new RoleCreateRequest(
                "ANALYST", "Analyste de performance", Set.of("MACHINE_READ")
        );
        roleService.createRole(createReq);

        RoleUpdateRequest updateReq = new RoleUpdateRequest(
                "Analyste senior mis à jour",
                Set.of("MACHINE_READ", "READINGS_READ", "DASHBOARD_READ", "ALERT_READ")
        );

        mockMvc.perform(put("/api/roles/ANALYST")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Analyste senior mis à jour"))
                .andExpect(jsonPath("$.privileges.length()").value(4));
    }

    @Test
    @DisplayName("Part C: System roles (ADMIN, OPERATOR, etc.) cannot be deleted (400 Bad Request)")
    void testSystemRolesCannotBeDeleted() throws Exception {
        mockMvc.perform(delete("/api/roles/ADMIN")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/roles/OPERATOR")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Part C: Custom role in use by active users cannot be deleted (409 Conflict)")
    void testRoleInUseCannotBeDeleted() throws Exception {
        roleService.createRole(new RoleCreateRequest("DISPATCHER", "Répartiteur", Set.of("MACHINE_READ")));

        // Assigner ce rôle personnalisé à un utilisateur
        User dispatcherUser = new User("John", "Dispatcher", "dispatcher@smartfactory.com",
                passwordEncoder.encode("pass"), Role.OPERATOR, "ACTIVE");
        dispatcherUser.setCustomRoleName("DISPATCHER");
        userRepository.save(dispatcherUser);

        mockMvc.perform(delete("/api/roles/DISPATCHER")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Part C: Custom role not in use can be deleted cleanly")
    void testDeleteUnusedCustomRole() throws Exception {
        roleService.createRole(new RoleCreateRequest("TEMPORARY_ROLE", "Rôle temporaire", Set.of("MACHINE_READ")));

        mockMvc.perform(delete("/api/roles/TEMPORARY_ROLE")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assertThat(roleRepository.existsByNameIgnoreCase("TEMPORARY_ROLE")).isFalse();
    }
}
