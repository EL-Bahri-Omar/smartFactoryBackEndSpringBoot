package com.smartfactory.controller;

import com.smartfactory.dto.ModuleCreateRequest;
import com.smartfactory.dto.ModulePrivilegeCreateRequest;
import com.smartfactory.dto.ModuleResponseDto;
import com.smartfactory.dto.ModuleUpdateRequest;
import com.smartfactory.entity.ModulePrivilege;
import com.smartfactory.service.FunctionalModuleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Contrôleur REST pour la gestion dynamique des modules fonctionnels et de leurs privilèges (Partie 2.4).
 */
@RestController
@RequestMapping("/api/modules")
public class FunctionalModuleController {

    private final FunctionalModuleService moduleService;

    public FunctionalModuleController(FunctionalModuleService moduleService) {
        this.moduleService = moduleService;
    }

    /**
     * Liste tous les modules fonctionnels configurés.
     * GET /api/modules
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_READ', 'USER_READ')")
    public ResponseEntity<List<ModuleResponseDto>> getAllModules() {
        return ResponseEntity.ok(moduleService.getAllModules());
    }

    /**
     * Récupère le détail d'un module par identifiant ou code.
     * GET /api/modules/{idOrCode}
     */
    @GetMapping("/{idOrCode}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_READ', 'USER_READ')")
    public ResponseEntity<ModuleResponseDto> getModule(@PathVariable String idOrCode) {
        return ResponseEntity.ok(moduleService.getModule(idOrCode));
    }

    /**
     * Crée un nouveau module fonctionnel.
     * POST /api/modules
     */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CREATE')")
    public ResponseEntity<ModuleResponseDto> createModule(@Valid @RequestBody ModuleCreateRequest request) {
        ModuleResponseDto created = moduleService.createModule(request);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    /**
     * Modifie un module fonctionnel existant.
     * PUT /api/modules/{idOrCode}
     */
    @PutMapping("/{idOrCode}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_UPDATE')")
    public ResponseEntity<ModuleResponseDto> updateModule(
            @PathVariable String idOrCode,
            @RequestBody ModuleUpdateRequest request
    ) {
        return ResponseEntity.ok(moduleService.updateModule(idOrCode, request));
    }

    /**
     * Supprime un module fonctionnel si non utilisé.
     * DELETE /api/modules/{idOrCode}
     */
    @DeleteMapping("/{idOrCode}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DELETE')")
    public ResponseEntity<Void> deleteModule(@PathVariable String idOrCode) {
        moduleService.deleteModule(idOrCode);
        return ResponseEntity.noContent().build();
    }

    /**
     * Liste les privilèges d'un module fonctionnel.
     * GET /api/modules/{idOrCode}/privileges
     */
    @GetMapping("/{idOrCode}/privileges")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_READ', 'USER_READ')")
    public ResponseEntity<List<ModulePrivilege>> getModulePrivileges(@PathVariable String idOrCode) {
        return ResponseEntity.ok(moduleService.getModulePrivileges(idOrCode));
    }

    /**
     * Ajoute un nouveau privilège à un module fonctionnel.
     * POST /api/modules/{idOrCode}/privileges
     */
    @PostMapping("/{idOrCode}/privileges")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_UPDATE')")
    public ResponseEntity<ModuleResponseDto> addPrivilege(
            @PathVariable String idOrCode,
            @Valid @RequestBody ModulePrivilegeCreateRequest request
    ) {
        ModuleResponseDto updated = moduleService.addPrivilegeToModule(idOrCode, request);
        return new ResponseEntity<>(updated, HttpStatus.CREATED);
    }

    /**
     * Retire un privilège d'un module fonctionnel.
     * DELETE /api/modules/{idOrCode}/privileges/{privilegeCode}
     */
    @DeleteMapping("/{idOrCode}/privileges/{privilegeCode}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_UPDATE')")
    public ResponseEntity<ModuleResponseDto> removePrivilege(
            @PathVariable String idOrCode,
            @PathVariable String privilegeCode
    ) {
        return ResponseEntity.ok(moduleService.removePrivilegeFromModule(idOrCode, privilegeCode));
    }
}
