package com.smartfactory.controller;

import com.smartfactory.entity.ModulePrivilege;
import com.smartfactory.entity.RoleDocument;
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
    public ResponseEntity<List<RoleDocument>> getAllRoles() {
        List<RoleDocument> roles = roleService.getAllRoles();
        return ResponseEntity.ok(roles);
    }

    /**
     * Liste tous les privilèges disponibles dans le catalogue SmartFactory.
     * GET /api/roles/privileges
     */
    @GetMapping("/privileges")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_READ')")
    public ResponseEntity<List<ModulePrivilege>> getAllPrivileges() {
        List<ModulePrivilege> privileges = roleService.getAllAvailablePrivileges();
        return ResponseEntity.ok(privileges);
    }

    /**
     * Récupère le détail d'un rôle par son identifiant ou son nom.
     * GET /api/roles/{idOrName}
     */
    @GetMapping("/{idOrName}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_READ')")
    public ResponseEntity<RoleDocument> getRole(@PathVariable String idOrName) {
        RoleDocument role = roleService.getRole(idOrName);
        return ResponseEntity.ok(role);
    }

    /**
     * Crée un nouveau rôle personnalisé.
     * POST /api/roles
     */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CREATE')")
    public ResponseEntity<RoleDocument> createRole(@Valid @RequestBody RoleDocument request) {
        RoleDocument created = roleService.createRole(request);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    /**
     * Met à jour un rôle existant (description, liste de privilèges).
     * PUT /api/roles/{idOrName}
     */
    @PutMapping("/{idOrName}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_UPDATE')")
    public ResponseEntity<RoleDocument> updateRole(
            @PathVariable String idOrName,
            @RequestBody RoleDocument request
    ) {
        RoleDocument updated = roleService.updateRole(idOrName, request);
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
    public ResponseEntity<RoleDocument> addPrivilegesToRole(
            @PathVariable String idOrName,
            @RequestBody Set<String> privileges
    ) {
        RoleDocument updated = roleService.addPrivilegesToRole(idOrName, privileges);
        return ResponseEntity.ok(updated);
    }

    /**
     * Retire un privilège d'un rôle existant.
     * DELETE /api/roles/{idOrName}/privileges/{privilege}
     */
    @DeleteMapping("/{idOrName}/privileges/{privilege}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_UPDATE')")
    public ResponseEntity<RoleDocument> removePrivilegeFromRole(
            @PathVariable String idOrName,
            @PathVariable String privilege
    ) {
        RoleDocument updated = roleService.removePrivilegeFromRole(idOrName, privilege);
        return ResponseEntity.ok(updated);
    }
}
