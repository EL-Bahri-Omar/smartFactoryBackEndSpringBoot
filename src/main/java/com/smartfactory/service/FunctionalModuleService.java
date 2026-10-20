package com.smartfactory.service;

import com.smartfactory.dto.ModuleCreateRequest;
import com.smartfactory.dto.ModulePrivilegeCreateRequest;
import com.smartfactory.dto.ModuleResponseDto;
import com.smartfactory.dto.ModuleUpdateRequest;
import com.smartfactory.entity.FunctionalModule;
import com.smartfactory.entity.ModulePrivilege;
import com.smartfactory.entity.RoleDocument;
import com.smartfactory.repository.FunctionalModuleRepository;
import com.smartfactory.repository.RoleRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service métier de gestion dynamique des modules fonctionnels et de leurs privilèges (Partie 2.4).
 */
@Service
public class FunctionalModuleService {

    private static final Logger log = LoggerFactory.getLogger(FunctionalModuleService.class);

    private final FunctionalModuleRepository moduleRepository;
    private final RoleRepository roleRepository;

    public FunctionalModuleService(FunctionalModuleRepository moduleRepository, RoleRepository roleRepository) {
        this.moduleRepository = moduleRepository;
        this.roleRepository = roleRepository;
    }

    @PostConstruct
    public void init() {
        initializeDefaultModules();
    }

    public List<ModuleResponseDto> getAllModules() {
        return moduleRepository.findAll().stream()
                .map(this::toResponseDto)
                .collect(Collectors.toList());
    }

    public ModuleResponseDto getModule(String idOrCode) {
        FunctionalModule module = findModuleByIdOrCode(idOrCode);
        return toResponseDto(module);
    }

    public ModuleResponseDto createModule(ModuleCreateRequest request) {
        if (request == null || !StringUtils.hasText(request.getCode()) || !StringUtils.hasText(request.getName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR: Le code et le nom du module sont obligatoires");
        }

        String normalizedCode = request.getCode().trim().toUpperCase().replaceAll("[^A-Z0-9_]", "_");

        if (moduleRepository.existsByCodeIgnoreCase(normalizedCode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "MODULE_ALREADY_EXISTS: Un module avec le code '" + normalizedCode + "' existe déjà");
        }

        FunctionalModule module = new FunctionalModule(
                normalizedCode,
                request.getName().trim(),
                request.getDescription(),
                false
        );

        // Ajoute par défaut le privilège de consultation
        String defaultPrivCode = normalizedCode + ":CONSULTER";
        module.addPrivilege(new ModulePrivilege("CONSULTER", defaultPrivCode, "Consulter " + request.getName(), "Consulter les données du module " + normalizedCode));

        FunctionalModule saved = moduleRepository.save(module);
        log.info("Nouveau module fonctionnel créé : [{}] ({})", saved.getCode(), saved.getName());
        return toResponseDto(saved);
    }

    public ModuleResponseDto updateModule(String idOrCode, ModuleUpdateRequest request) {
        FunctionalModule module = findModuleByIdOrCode(idOrCode);

        if (request.getName() != null && StringUtils.hasText(request.getName())) {
            module.setName(request.getName().trim());
        }
        if (request.getDescription() != null) {
            module.setDescription(request.getDescription().trim());
        }
        if (request.getActive() != null) {
            module.setActive(request.getActive());
        }

        module.setUpdatedAt(Instant.now());
        FunctionalModule saved = moduleRepository.save(module);
        log.info("Module fonctionnel mis à jour : [{}]", saved.getCode());
        return toResponseDto(saved);
    }

    public void deleteModule(String idOrCode) {
        FunctionalModule module = findModuleByIdOrCode(idOrCode);

        if (module.isSystemModule()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "SYSTEM_MODULE_PROTECTED: Les modules système intégrés ne peuvent pas être supprimés");
        }

        // Vérifier si des privilèges de ce module sont utilisés par des rôles
        Set<String> modulePrivCodes = module.getPrivileges().stream()
                .map(ModulePrivilege::getCode)
                .collect(Collectors.toSet());

        List<String> referencingRoles = new ArrayList<>();
        for (RoleDocument role : roleRepository.findAll()) {
            for (String priv : role.getPrivileges()) {
                if (modulePrivCodes.contains(priv) || priv.startsWith(module.getCode() + ":")) {
                    referencingRoles.add(role.getName());
                    break;
                }
            }
        }

        if (!referencingRoles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "MODULE_IN_USE: Impossible de supprimer le module '" + module.getCode() +
                            "' car ses privilèges sont encore attribués au(x) rôle(s) : " + String.join(", ", referencingRoles));
        }

        moduleRepository.delete(module);
        log.info("Module fonctionnel supprimé : [{}]", module.getCode());
    }

    public ModuleResponseDto addPrivilegeToModule(String idOrCode, ModulePrivilegeCreateRequest request) {
        FunctionalModule module = findModuleByIdOrCode(idOrCode);

        if (request == null || !StringUtils.hasText(request.getAction())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR: L'action du privilège est obligatoire");
        }

        String action = request.getAction().trim().toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        String fullPrivCode = module.getCode() + ":" + action;

        String privName = StringUtils.hasText(request.getName()) ? request.getName().trim() : (action + " " + module.getName());
        String privDesc = StringUtils.hasText(request.getDescription()) ? request.getDescription().trim() : ("Droit de " + action + " sur le module " + module.getCode());

        ModulePrivilege priv = new ModulePrivilege(action, fullPrivCode, privName, privDesc);
        module.addPrivilege(priv);
        module.setUpdatedAt(Instant.now());

        FunctionalModule saved = moduleRepository.save(module);
        log.info("Privilège [{}] ajouté au module [{}]", fullPrivCode, module.getCode());
        return toResponseDto(saved);
    }

    public List<ModulePrivilege> getModulePrivileges(String idOrCode) {
        FunctionalModule module = findModuleByIdOrCode(idOrCode);
        return module.getPrivileges();
    }

    public ModuleResponseDto removePrivilegeFromModule(String idOrCode, String privilegeCode) {
        FunctionalModule module = findModuleByIdOrCode(idOrCode);
        if (!StringUtils.hasText(privilegeCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR: Le code du privilège est obligatoire");
        }

        String targetCode = privilegeCode.trim().toUpperCase();

        // Vérifier si des rôles utilisent ce privilège
        List<String> referencingRoles = roleRepository.findAll().stream()
                .filter(r -> r.getPrivileges().contains(targetCode))
                .map(RoleDocument::getName)
                .collect(Collectors.toList());

        if (!referencingRoles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PRIVILEGE_IN_USE: Le privilège '" + targetCode + "' est utilisé par les rôles : " + String.join(", ", referencingRoles));
        }

        boolean removed = module.removePrivilege(targetCode);
        if (!removed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PRIVILEGE_NOT_FOUND: Privilège '" + targetCode + "' introuvable dans le module");
        }

        module.setUpdatedAt(Instant.now());
        FunctionalModule saved = moduleRepository.save(module);
        log.info("Privilège [{}] retiré du module [{}]", targetCode, module.getCode());
        return toResponseDto(saved);
    }

    public Set<String> getAllAvailablePrivilegeCodes() {
        Set<String> codes = new HashSet<>();
        for (FunctionalModule m : moduleRepository.findByActiveTrue()) {
            for (ModulePrivilege p : m.getPrivileges()) {
                codes.add(p.getCode());
            }
        }
        return codes;
    }

    public FunctionalModule findModuleByIdOrCode(String idOrCode) {
        if (!StringUtils.hasText(idOrCode)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MODULE_NOT_FOUND: Identifiant de module manquant");
        }
        String q = idOrCode.trim();
        Optional<FunctionalModule> byId = moduleRepository.findById(q);
        if (byId.isPresent()) {
            return byId.get();
        }
        Optional<FunctionalModule> byCode = moduleRepository.findByCodeIgnoreCase(q);
        if (byCode.isPresent()) {
            return byCode.get();
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MODULE_NOT_FOUND: Module introuvable avec l'identifiant '" + idOrCode + "'");
    }

    /**
     * Initialisation idempotente des modules système par défaut et de leurs privilèges.
     */
    public void initializeDefaultModules() {
        createDefaultModuleIfAbsent("USER", "Gestion des Utilisateurs", "Gestion des comptes, profils et rôles",
                List.of(
                        new ModulePrivilege("CONSULTER", "USER:CONSULTER", "Consulter Utilisateurs", "Afficher la liste et le détail des utilisateurs"),
                        new ModulePrivilege("AJOUTER", "USER:AJOUTER", "Ajouter Utilisateur", "Créer un nouveau compte utilisateur"),
                        new ModulePrivilege("MODIFIER", "USER:MODIFIER", "Modifier Utilisateur", "Mettre à jour les informations d'un utilisateur"),
                        new ModulePrivilege("SUPPRIMER", "USER:SUPPRIMER", "Supprimer Utilisateur", "Supprimer ou désactiver un compte"),
                        new ModulePrivilege("GERER_PERMISSIONS", "USER:GERER_PERMISSIONS", "Gérer Permissions", "Attribuer des privilèges personnalisés")
                )
        );

        createDefaultModuleIfAbsent("MACHINE", "Gestion des Machines", "Gestion du parc de machines industrielles",
                List.of(
                        new ModulePrivilege("CONSULTER", "MACHINE:CONSULTER", "Consulter Machines", "Consulter les machines et leurs caractéristiques"),
                        new ModulePrivilege("AJOUTER", "MACHINE:AJOUTER", "Ajouter Machine", "Créer une nouvelle machine industrielle"),
                        new ModulePrivilege("MODIFIER", "MACHINE:MODIFIER", "Modifier Machine", "Modifier la configuration d'une machine"),
                        new ModulePrivilege("SUPPRIMER", "MACHINE:SUPPRIMER", "Supprimer Machine", "Supprimer une machine du parc")
                )
        );

        createDefaultModuleIfAbsent("SENSOR", "Capteurs & Télémétrie", "Surveillance des capteurs et ingestion de télémétrie",
                List.of(
                        new ModulePrivilege("CONSULTER", "SENSOR:CONSULTER", "Consulter Capteurs", "Consulter les capteurs et valeurs temps réel"),
                        new ModulePrivilege("HISTORIQUE", "SENSOR:HISTORIQUE", "Consulter Historique", "Consulter l'historique des mesures et graphiques")
                )
        );

        createDefaultModuleIfAbsent("THRESHOLD", "Seuils de Surveillance", "Configuration des seuils physiques d'alerte",
                List.of(
                        new ModulePrivilege("CONSULTER", "THRESHOLD:CONSULTER", "Consulter Seuils", "Afficher les seuils min/max configurés"),
                        new ModulePrivilege("MODIFIER", "THRESHOLD:MODIFIER", "Configurer Seuils", "Définir ou modifier les seuils d'alerte")
                )
        );

        createDefaultModuleIfAbsent("ALERT", "Gestion des Alertes", "Surveillance des anomalies et traitement des alertes",
                List.of(
                        new ModulePrivilege("CONSULTER", "ALERT:CONSULTER", "Consulter Alertes", "Consulter les alertes et anomalies actives"),
                        new ModulePrivilege("ACQUITTER", "ALERT:ACQUITTER", "Acquitter Alerte", "Prendre en charge et acquitter une alerte"),
                        new ModulePrivilege("MODIFIER", "ALERT:MODIFIER", "Gérer Alertes", "Modifier ou clore une alerte")
                )
        );

        createDefaultModuleIfAbsent("MAINTENANCE", "Maintenance & Interventions", "Gestion des ordres de travail, tickets et diagnostics",
                List.of(
                        new ModulePrivilege("CONSULTER", "MAINTENANCE:CONSULTER", "Consulter Interventions", "Consulter les interventions de maintenance"),
                        new ModulePrivilege("CREER", "MAINTENANCE:CREER", "Créer Intervention", "Créer une nouvelle intervention de maintenance"),
                        new ModulePrivilege("ASSIGNER", "MAINTENANCE:ASSIGNER", "Assigner Technicien", "Affecter un technicien à une intervention"),
                        new ModulePrivilege("DIAGNOSTIQUER", "MAINTENANCE:DIAGNOSTIQUER", "Enregistrer Diagnostic", "Documenter le diagnostic et cause"),
                        new ModulePrivilege("CLOTURER", "MAINTENANCE:CLOTURER", "Clôturer Intervention", "Valider et terminer l'intervention")
                )
        );

        createDefaultModuleIfAbsent("ZONE", "Zones d'Atelier", "Organisation spatiale des machines par zone",
                List.of(
                        new ModulePrivilege("CONSULTER", "ZONE:CONSULTER", "Consulter Zones", "Consulter les zones géographiques de l'usine"),
                        new ModulePrivilege("AJOUTER", "ZONE:AJOUTER", "Ajouter Zone", "Créer une nouvelle zone industrielle"),
                        new ModulePrivilege("MODIFIER", "ZONE:MODIFIER", "Modifier Zone", "Modifier les paramètres d'une zone"),
                        new ModulePrivilege("SUPPRIMER", "ZONE:SUPPRIMER", "Supprimer Zone", "Supprimer une zone")
                )
        );

        createDefaultModuleIfAbsent("GROUP", "Groupes d'Opérateurs", "Gestion des équipes de supervision d'ateliers",
                List.of(
                        new ModulePrivilege("CONSULTER", "GROUP:CONSULTER", "Consulter Groupes", "Consulter les équipes et affectations"),
                        new ModulePrivilege("AJOUTER", "GROUP:AJOUTER", "Ajouter Groupe", "Créer un nouveau groupe d'opérateurs"),
                        new ModulePrivilege("MODIFIER", "GROUP:MODIFIER", "Modifier Groupe", "Modifier les membres et superviseurs"),
                        new ModulePrivilege("SUPPRIMER", "GROUP:SUPPRIMER", "Supprimer Groupe", "Supprimer un groupe")
                )
        );

        createDefaultModuleIfAbsent("DASHBOARD", "Tableaux de Bord", "Indicateurs globaux et KPI industriels",
                List.of(
                        new ModulePrivilege("CONSULTER", "DASHBOARD:CONSULTER", "Consulter Dashboard", "Consulter la vue synthétique de l'usine")
                )
        );

        createDefaultModuleIfAbsent("ROLE", "Gestion des Rôles", "Administration des rôles et modèles de privilèges",
                List.of(
                        new ModulePrivilege("CONSULTER", "ROLE:CONSULTER", "Consulter Rôles", "Consulter les rôles et leurs privilèges"),
                        new ModulePrivilege("AJOUTER", "ROLE:AJOUTER", "Créer Rôle", "Créer un nouveau rôle personnalisé"),
                        new ModulePrivilege("MODIFIER", "ROLE:MODIFIER", "Modifier Rôle", "Modifier les privilèges d'un rôle"),
                        new ModulePrivilege("SUPPRIMER", "ROLE:SUPPRIMER", "Supprimer Rôle", "Supprimer un rôle personnalisé")
                )
        );
    }

    private void createDefaultModuleIfAbsent(String code, String name, String description, List<ModulePrivilege> defaultPrivs) {
        if (!moduleRepository.existsByCodeIgnoreCase(code)) {
            FunctionalModule module = new FunctionalModule(code, name, description, true);
            module.setPrivileges(new ArrayList<>(defaultPrivs));
            moduleRepository.save(module);
            log.info("Module système initialisé : [{}] avec {} privilèges", code, defaultPrivs.size());
        }
    }

    private ModuleResponseDto toResponseDto(FunctionalModule m) {
        return new ModuleResponseDto(
                m.getId(),
                m.getCode(),
                m.getName(),
                m.getDescription(),
                m.isActive(),
                m.isSystemModule(),
                m.getPrivileges(),
                m.getCreatedAt(),
                m.getUpdatedAt()
        );
    }
}
