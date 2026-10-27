package com.smartfactory.service;

import com.smartfactory.entity.FunctionalModule;
import com.smartfactory.entity.ModulePrivilege;
import com.smartfactory.repository.FunctionalModuleRepository;
import com.smartfactory.repository.RoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FunctionalModuleServiceTest {

    private FunctionalModuleRepository moduleRepository;
    private RoleRepository roleRepository;
    private FunctionalModuleService moduleService;

    @BeforeEach
    void setUp() {
        moduleRepository = Mockito.mock(FunctionalModuleRepository.class);
        roleRepository = Mockito.mock(RoleRepository.class);
        moduleService = new FunctionalModuleService(moduleRepository, roleRepository);
    }

    @Test
    @DisplayName("2.4 - Create new functional module with unique code")
    void testCreateModule() {
        FunctionalModule req = new FunctionalModule("INVENTORY", "Gestion des stocks", "Catégorie logistique", false);
        when(moduleRepository.existsByCodeIgnoreCase("INVENTORY")).thenReturn(false);
        when(moduleRepository.save(any(FunctionalModule.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FunctionalModule created = moduleService.createModule(req);
        assertThat(created.getCode()).isEqualTo("INVENTORY");
        assertThat(created.getName()).isEqualTo("Gestion des stocks");
        verify(moduleRepository, times(1)).save(any(FunctionalModule.class));
    }

    @Test
    @DisplayName("2.4 - Rejects duplicate functional module code")
    void testDuplicateModuleCodeRejected() {
        FunctionalModule req = new FunctionalModule("USER", "Gestion des utilisateurs", "Catégorie auth", false);
        when(moduleRepository.existsByCodeIgnoreCase("USER")).thenReturn(true);

        assertThatThrownBy(() -> moduleService.createModule(req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("existe déjà");
    }

    @Test
    @DisplayName("2.4 - Add privilege to module creates properly formatted code like USER:AJOUTER")
    void testAddPrivilegeToModule() {
        FunctionalModule userModule = new FunctionalModule("USER", "Module Utilisateurs", "Gestion des utilisateurs", false);
        userModule.setId("mod-user-1");
        when(moduleRepository.findById("mod-user-1")).thenReturn(Optional.of(userModule));
        when(moduleRepository.findByCodeIgnoreCase("mod-user-1")).thenReturn(Optional.of(userModule));
        when(moduleRepository.save(any(FunctionalModule.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ModulePrivilege req = new ModulePrivilege("AJOUTER", null, "Créer un utilisateur", "Droit de créer un compte");
        FunctionalModule updated = moduleService.addPrivilegeToModule("mod-user-1", req);

        assertThat(updated.getPrivileges()).isNotEmpty();
        assertThat(updated.getPrivileges().stream().anyMatch(p -> "USER:AJOUTER".equals(p.getCode()))).isTrue();
    }
}
