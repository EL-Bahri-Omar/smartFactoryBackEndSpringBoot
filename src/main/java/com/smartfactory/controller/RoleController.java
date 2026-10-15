package com.smartfactory.controller;

import com.smartfactory.dto.PrivilegeDto;
import com.smartfactory.dto.RoleCreateRequest;
import com.smartfactory.dto.RoleResponseDto;
import com.smartfactory.dto.RoleUpdateRequest;
import com.smartfactory.service.RoleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * Contrôleur REST pour la gestion dynamique des rôles et des privilèges (Partie C).
 * Seuls les utilisateurs disposant des privilèges appropriés peuvent administrer les rôles.
 */
@RestController
@RequestMapping("/api/roles")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    /**
     * Liste tous les rôles configurés dans le système.
     * GET /api/roles
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_READ')")
    public ResponseEntity<List<RoleResponseDto>> getAllRoles() {
        List<RoleResponseDto> roles = roleService.getAllRoles();
        return ResponseEntity.ok(roles);
    }

    /**
     * Liste tous les privilèges disponibles dans le catalogue SmartFactory.
     * GET /api/roles/privileges
     */
    @GetMapping("/privileges")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_READ')")
    public ResponseEntity<List<PrivilegeDto>> getAllPrivileges() {
        List<PrivilegeDto> privileges = roleService.getAllAvailablePrivileges();
        return ResponseEntity.ok(privileges);
    }

    /**
     * Récupère le détail d'un rôle par son identifiant ou son nom.
     * GET /api/roles/{idOrName}
     */
    @GetMapping("/{idOrName}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_READ')")
    public ResponseEntity<RoleResponseDto> getRole(@PathVariable String idOrName) {
        RoleResponseDto role = roleService.getRole(idOrName);
        return ResponseEntity.ok(role);
    }

    /**
     * Crée un nouveau rôle personnalisé.
     * POST /api/roles
     */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CREATE')")
    public ResponseEntity<RoleResponseDto> createRole(@Valid @RequestBody RoleCreateRequest request) {
        RoleResponseDto created = roleService.createRole(request);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    /**
     * Met à jour un rôle existant (description, liste de privilèges).
     * PUT /api/roles/{idOrName}
     */
    @PutMapping("/{idOrName}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_UPDATE')")
    public ResponseEntity<RoleResponseDto> updateRole(
            @PathVariable String idOrName,
            @Valid @RequestBody RoleUpdateRequest request
    ) {
        RoleResponseDto updated = roleService.updateRole(idOrName, request);
        return ResponseEntity.ok(updated);
    }

    /**
     * Supprime un rôle personnalisé (interdit pour les rôles système et ceux affectés).
     * DELETE /api/roles/{idOrName}
     */
    @DeleteMapping("/{idOrName}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DELETE')")
    public ResponseEntity<Void> deleteRole(@PathVariable String idOrName) {
        roleService.deleteRole(idOrName);
        return ResponseEntity.noContent().build();
    }

    /**
     * Consulte les privilèges associés à un rôle.
     * GET /api/roles/{idOrName}/privileges
     */
    @GetMapping("/{idOrName}/privileges")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_READ')")
    public ResponseEntity<Set<String>> getRolePrivileges(@PathVariable String idOrName) {
        Set<String> privileges = roleService.getRolePrivileges(idOrName);
        return ResponseEntity.ok(privileges);
    }

    /**
     * Attribue des privilèges à un rôle existant.
     * POST /api/roles/{idOrName}/privileges
     */
    @PostMapping("/{idOrName}/privileges")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_UPDATE')")
    public ResponseEntity<RoleResponseDto> addPrivilegesToRole(
            @PathVariable String idOrName,
            @RequestBody Set<String> privileges
    ) {
        RoleResponseDto updated = roleService.addPrivilegesToRole(idOrName, privileges);
        return ResponseEntity.ok(updated);
    }

    /**
     * Retire un privilège d'un rôle existant.
     * DELETE /api/roles/{idOrName}/privileges/{privilege}
     */
    @DeleteMapping("/{idOrName}/privileges/{privilege}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_UPDATE')")
    public ResponseEntity<RoleResponseDto> removePrivilegeFromRole(
            @PathVariable String idOrName,
            @PathVariable String privilege
    ) {
        RoleResponseDto updated = roleService.removePrivilegeFromRole(idOrName, privilege);
        return ResponseEntity.ok(updated);
    }
}
