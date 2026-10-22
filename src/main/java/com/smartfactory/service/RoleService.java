package com.smartfactory.service;

import com.smartfactory.dto.PrivilegeDto;
import com.smartfactory.dto.RoleCreateRequest;
import com.smartfactory.dto.RoleResponseDto;
import com.smartfactory.dto.RoleUpdateRequest;
import com.smartfactory.entity.RoleDocument;
import com.smartfactory.repository.RoleRepository;
import com.smartfactory.repository.UserRepository;
import com.smartfactory.security.Permission;
import com.smartfactory.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class RoleService {

    private static final Logger log = LoggerFactory.getLogger(RoleService.class);

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;

    /**
     * Cache mémoire des privilèges par rôle pour validation ultra-rapide sans latence DB sur les requêtes JWT.
     */
    private final ConcurrentHashMap<String, Set<String>> rolePrivilegesCache = new ConcurrentHashMap<>();

    public RoleService(RoleRepository roleRepository, UserRepository userRepository) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
    }

    /**
     * Liste tous les rôles configurés avec le nombre d'utilisateurs affectés.
     */
    public List<RoleResponseDto> getAllRoles() {
        return roleRepository.findAll().stream()
                .map(this::toResponseDto)
                .collect(Collectors.toList());
    }

    /**
     * Récupère un rôle par son identifiant MongoDB ou son nom unique.
     */
    public RoleResponseDto getRole(String idOrName) {
        RoleDocument doc = findRoleByIdOrName(idOrName);
        return toResponseDto(doc);
    }

    /**
     * Crée un nouveau rôle personnalisé avec ses privilèges associés.
     */
    public RoleResponseDto createRole(RoleCreateRequest request) {
        if (request == null || !StringUtils.hasText(request.getName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR: Le nom du rôle est obligatoire");
        }

        String normalizedName = request.getName().trim().toUpperCase();

        if (roleRepository.existsByNameIgnoreCase(normalizedName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "ROLE_ALREADY_EXISTS: Un rôle avec le nom '" + normalizedName + "' existe déjà");
        }

        Set<String> validatedPrivileges = validateAndNormalizePrivileges(request.getPrivileges());

        RoleDocument doc = new RoleDocument(
                normalizedName,
                request.getDescription(),
                validatedPrivileges,
                false
        );

        RoleDocument saved = roleRepository.save(doc);
        rolePrivilegesCache.put(normalizedName, saved.getPrivileges());
        log.info("Rôle personnalisé créé avec succès: [{}] avec {} privilèges", normalizedName, saved.getPrivileges().size());

        return toResponseDto(saved);
    }

    /**
     * Met à jour la description et/ou les privilèges d'un rôle existant.
     */
    public RoleResponseDto updateRole(String idOrName, RoleUpdateRequest request) {
        RoleDocument doc = findRoleByIdOrName(idOrName);

        if (request.getDescription() != null) {
            doc.setDescription(request.getDescription());
        }

        if (request.getPrivileges() != null) {
            Set<String> validatedPrivileges = validateAndNormalizePrivileges(request.getPrivileges());
            doc.setPrivileges(validatedPrivileges);
        }

        doc.setUpdatedAt(Instant.now());
        RoleDocument saved = roleRepository.save(doc);
        rolePrivilegesCache.put(saved.getName(), saved.getPrivileges());
        log.info("Rôle mis à jour: [{}] ({} privilèges)", saved.getName(), saved.getPrivileges().size());

        return toResponseDto(saved);
    }

    /**
     * Supprime un rôle si autorisé (interdit pour les rôles système et les rôles encore attribués).
     */
    public void deleteRole(String idOrName) {
        RoleDocument doc = findRoleByIdOrName(idOrName);

        if (doc.isSystemRole()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "SYSTEM_ROLE_CANNOT_BE_DELETED: Les rôles système intégrés ne peuvent pas être supprimés");
        }

        long assignedUsers = countUsersWithRole(doc.getName());
        if (assignedUsers > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "ROLE_IN_USE: Impossible de supprimer le rôle '" + doc.getName() +
                            "' car il est actuellement attribué à " + assignedUsers + " utilisateur(s)");
        }

        roleRepository.delete(doc);
        rolePrivilegesCache.remove(doc.getName());
        log.info("Rôle supprimé avec succès: [{}]", doc.getName());
    }

    /**
     * Liste tous les privilèges disponibles dans le catalogue SmartFactory.
     */
    public List<PrivilegeDto> getAllAvailablePrivileges() {
        return Arrays.stream(Permission.values())
                .map(p -> new PrivilegeDto(p.getCode(), p.name(), p.getDescription(), p.getCategory()))
                .sorted(Comparator.comparing(PrivilegeDto::getCategory).thenComparing(PrivilegeDto::getCode))
                .collect(Collectors.toList());
    }

    /**
     * Attribue des privilèges supplémentaires à un rôle.
     */
    public RoleResponseDto addPrivilegesToRole(String idOrName, Set<String> privileges) {
        RoleDocument doc = findRoleByIdOrName(idOrName);
        Set<String> toAdd = validateAndNormalizePrivileges(privileges);

        Set<String> current = new HashSet<>(doc.getPrivileges());
        current.addAll(toAdd);
        doc.setPrivileges(current);
        doc.setUpdatedAt(Instant.now());

        RoleDocument saved = roleRepository.save(doc);
        rolePrivilegesCache.put(saved.getName(), saved.getPrivileges());
        log.info("Privilèges ajoutés au rôle [{}]: {}", saved.getName(), toAdd);
        return toResponseDto(saved);
    }

    /**
     * Retire un privilège d'un rôle.
     */
    public RoleResponseDto removePrivilegeFromRole(String idOrName, String privilegeCode) {
        RoleDocument doc = findRoleByIdOrName(idOrName);
        if (!StringUtils.hasText(privilegeCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Privilege code is required");
        }

        String normalizedCode = privilegeCode.trim().toUpperCase();
        Set<String> current = new HashSet<>(doc.getPrivileges());
        current.remove(normalizedCode);
        doc.setPrivileges(current);
        doc.setUpdatedAt(Instant.now());

        RoleDocument saved = roleRepository.save(doc);
        rolePrivilegesCache.put(saved.getName(), saved.getPrivileges());
        log.info("Privilège [{}] retiré du rôle [{}]", normalizedCode, saved.getName());
        return toResponseDto(saved);
    }

    /**
     * Récupère la liste des privilèges associés à un rôle.
     */
    public Set<String> getRolePrivileges(String idOrName) {
        RoleDocument doc = findRoleByIdOrName(idOrName);
        return doc.getPrivileges();
    }

    /**
     * Résout les privilèges effectifs pour un nom de rôle donné (utilisé par le filtre de sécurité).
     */
    public Set<String> getPrivilegesForRoleName(String roleName) {
        if (!StringUtils.hasText(roleName)) {
            return Collections.emptySet();
        }

        String normalized = roleName.trim().toUpperCase();
        Set<String> cached = rolePrivilegesCache.get(normalized);
        if (cached != null) {
            return cached;
        }

        Optional<RoleDocument> docOpt = roleRepository.findByNameIgnoreCase(normalized);
        if (docOpt.isPresent()) {
            Set<String> privs = docOpt.get().getPrivileges();
            rolePrivilegesCache.put(normalized, privs);
            return privs;
        }

        // Fallback sur le Role enum si pas encore en base
        try {
            Role enumRole = Role.valueOf(normalized);
            Set<String> enumPrivs = enumRole.getDefaultPermissions().stream()
                    .map(Permission::name)
                    .collect(Collectors.toSet());
            rolePrivilegesCache.put(normalized, enumPrivs);
            return enumPrivs;
        } catch (IllegalArgumentException ignored) {
            return Collections.emptySet();
        }
    }

    /**
     * Initialisation idempotente des rôles système par défaut dans la base MongoDB.
     */
    public void initializeDefaultRoles() {
        for (Role role : Role.values()) {
            String roleName = role.name();
            Optional<RoleDocument> existing = roleRepository.findByNameIgnoreCase(roleName);

            Set<String> defaultPrivs = role.getDefaultPermissions().stream()
                    .map(Permission::name)
                    .collect(Collectors.toSet());

            if (existing.isEmpty()) {
                RoleDocument doc = new RoleDocument(
                        roleName,
                        role.getLabel(),
                        defaultPrivs,
                        true
                );
                roleRepository.save(doc);
                rolePrivilegesCache.put(roleName, defaultPrivs);
                log.info("Rôle système initialisé: [{}] avec {} privilèges par défaut", roleName, defaultPrivs.size());
            } else {
                RoleDocument doc = existing.get();
                if (role == Role.OPERATOR && doc.isSystemRole()) {
                    Set<String> currentPrivs = new HashSet<>(doc.getPrivileges());
                    boolean removed = currentPrivs.removeIf(p -> "ALERT_READ".equalsIgnoreCase(p) || "ALERT:CONSULTER".equalsIgnoreCase(p));
                    if (removed) {
                        doc.setPrivileges(currentPrivs);
                        roleRepository.save(doc);
                        log.info("Rôle système OPERATOR assaini : suppression des privilèges d'alerte");
                    }
                    rolePrivilegesCache.put(roleName, currentPrivs);
                } else {
                    rolePrivilegesCache.put(roleName, doc.getPrivileges());
                }
            }
        }
    }

    private RoleDocument findRoleByIdOrName(String idOrName) {
        if (!StringUtils.hasText(idOrName)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND: Identifiant de rôle manquant");
        }
        String query = idOrName.trim();
        Optional<RoleDocument> byId = roleRepository.findById(query);
        if (byId.isPresent()) {
            return byId.get();
        }
        Optional<RoleDocument> byName = roleRepository.findByNameIgnoreCase(query);
        if (byName.isPresent()) {
            return byName.get();
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND: Rôle introuvable avec l'identifiant '" + idOrName + "'");
    }

    private Set<String> validateAndNormalizePrivileges(Set<String> rawPrivileges) {
        if (rawPrivileges == null || rawPrivileges.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> validated = new HashSet<>();
        for (String raw : rawPrivileges) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            String trimmed = raw.trim().toUpperCase();
            Permission perm = Permission.fromCode(trimmed);
            if (perm != null) {
                validated.add(perm.name());
            } else if (trimmed.contains(":")) {
                validated.add(trimmed);
            } else {
                validated.add(trimmed);
            }
        }
        return validated;
    }

    private long countUsersWithRole(String roleName) {
        long count = 0;
        try {
            Role enumRole = Role.valueOf(roleName.toUpperCase());
            count += userRepository.countByRole(enumRole);
        } catch (IllegalArgumentException ignored) {
        }
        count += userRepository.countByCustomRoleName(roleName.toUpperCase());
        return count;
    }

    private RoleResponseDto toResponseDto(RoleDocument doc) {
        long count = countUsersWithRole(doc.getName());
        return new RoleResponseDto(
                doc.getId(),
                doc.getName(),
                doc.getDescription(),
                doc.getPrivileges(),
                doc.isSystemRole(),
                count,
                doc.getCreatedAt(),
                doc.getUpdatedAt()
        );
    }
}
