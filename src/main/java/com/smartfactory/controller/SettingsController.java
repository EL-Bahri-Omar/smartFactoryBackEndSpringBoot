package com.smartfactory.controller;

import com.smartfactory.entity.User;
import com.smartfactory.repository.UserRepository;
import com.smartfactory.security.Role;
import com.smartfactory.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Contrôleur REST pour la gestion des paramètres globaux et des préférences de notification (Section 5).
 */
@RestController
public class SettingsController {

    private final UserRepository userRepository;

    public SettingsController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    private User resolveAuthenticatedUser(UserPrincipal currentUser) {
        if (currentUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Utilisateur non authentifié");
        }
        return userRepository.findById(currentUser.getId())
                .or(() -> userRepository.findByEmail(currentUser.getUsername()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable"));
    }

    /**
     * Récupère les paramètres de l'utilisateur connecté (Section 5).
     * GET /api/settings
     */
    @GetMapping("/api/settings")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> getSettings(@AuthenticationPrincipal UserPrincipal currentUser) {
        User user = resolveAuthenticatedUser(currentUser);

        Map<String, Object> general = new LinkedHashMap<>();
        general.put("factoryName", "SmartFactory");
        general.put("timezone", "UTC+01:00");
        general.put("language", "en");
        general.put("dateFormat", "DD/MM/YYYY");

        Map<String, Object> notifications = new LinkedHashMap<>();
        notifications.put("enableNotifications", user.isNotificationsEnabled());
        notifications.put("notificationsEnabled", user.isNotificationsEnabled());
        notifications.put("darkMode", false);

        Map<String, Object> machines = new LinkedHashMap<>();
        machines.put("autoRefreshSeconds", 0);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("general", general);
        response.put("notifications", notifications);
        response.put("machines", machines);
        response.put("notificationsEnabled", user.isNotificationsEnabled());

        return ResponseEntity.ok(response);
    }

    /**
     * Met à jour les paramètres de l'utilisateur connecté (Section 5).
     * PATCH /api/settings ou PUT /api/settings
     */
    @RequestMapping(value = "/api/settings", method = {RequestMethod.PATCH, RequestMethod.PUT})
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> updateSettings(
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        User user = resolveAuthenticatedUser(currentUser);

        if (body != null) {
            if (body.containsKey("notifications") && body.get("notifications") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> notifMap = (Map<String, Object>) body.get("notifications");
                if (notifMap.containsKey("enableNotifications")) {
                    user.setNotificationsEnabled(Boolean.TRUE.equals(notifMap.get("enableNotifications")));
                } else if (notifMap.containsKey("notificationsEnabled")) {
                    user.setNotificationsEnabled(Boolean.TRUE.equals(notifMap.get("notificationsEnabled")));
                }
            } else if (body.containsKey("enableNotifications")) {
                user.setNotificationsEnabled(Boolean.TRUE.equals(body.get("enableNotifications")));
            } else if (body.containsKey("notificationsEnabled")) {
                user.setNotificationsEnabled(Boolean.TRUE.equals(body.get("notificationsEnabled")));
            }
        }

        userRepository.save(user);

        return getSettings(currentUser);
    }

    /**
     * Récupère les préférences de notification de l'utilisateur connecté.
     * GET /api/notifications/preferences
     */
    @GetMapping("/api/notifications/preferences")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> getNotificationPreferences(@AuthenticationPrincipal UserPrincipal currentUser) {
        User user = resolveAuthenticatedUser(currentUser);
        Map<String, Object> prefs = new LinkedHashMap<>();
        prefs.put("userId", user.getId());
        prefs.put("notificationsEnabled", user.isNotificationsEnabled());
        prefs.put("enableNotifications", user.isNotificationsEnabled());
        return ResponseEntity.ok(prefs);
    }

    /**
     * Met à jour les préférences de notification de l'utilisateur connecté.
     * PUT /api/notifications/preferences ou PATCH /api/notifications/preferences
     */
    @RequestMapping(value = "/api/notifications/preferences", method = {RequestMethod.PUT, RequestMethod.PATCH})
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> updateNotificationPreferences(
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        User user = resolveAuthenticatedUser(currentUser);

        if (body != null) {
            if (body.containsKey("notificationsEnabled")) {
                user.setNotificationsEnabled(Boolean.TRUE.equals(body.get("notificationsEnabled")));
            } else if (body.containsKey("enableNotifications")) {
                user.setNotificationsEnabled(Boolean.TRUE.equals(body.get("enableNotifications")));
            }
        }

        userRepository.save(user);

        return getNotificationPreferences(currentUser);
    }

    /**
     * Récupère les préférences de notification d'un utilisateur spécifique.
     * GET /api/users/{id}/preferences
     */
    @GetMapping("/api/users/{id}/preferences")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> getUserPreferences(
            @PathVariable String id,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        boolean isSelf = currentUser != null && (id.equalsIgnoreCase(currentUser.getId()) || id.equalsIgnoreCase(currentUser.getUsername()));
        boolean isAdmin = currentUser != null && currentUser.getRole() == Role.ADMIN;

        if (!isSelf && !isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé aux préférences d'un autre utilisateur");
        }

        User user = userRepository.findById(id)
                .or(() -> userRepository.findByEmail(id))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable : " + id));

        Map<String, Object> prefs = new LinkedHashMap<>();
        prefs.put("userId", user.getId());
        prefs.put("notificationsEnabled", user.isNotificationsEnabled());
        prefs.put("enableNotifications", user.isNotificationsEnabled());
        return ResponseEntity.ok(prefs);
    }

    /**
     * Met à jour les préférences d'un utilisateur spécifique avec contrôle d'autorisation strict (Section 5.2).
     * PUT /api/users/{id}/preferences ou PATCH /api/users/{id}/preferences
     */
    @RequestMapping(value = "/api/users/{id}/preferences", method = {RequestMethod.PUT, RequestMethod.PATCH})
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> updateUserPreferences(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        boolean isSelf = currentUser != null && (id.equalsIgnoreCase(currentUser.getId()) || id.equalsIgnoreCase(currentUser.getUsername()));
        boolean isAdmin = currentUser != null && currentUser.getRole() == Role.ADMIN;

        if (!isSelf && !isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé : modification interdite des préférences d'un autre utilisateur");
        }

        User user = userRepository.findById(id)
                .or(() -> userRepository.findByEmail(id))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable : " + id));

        if (body != null) {
            if (body.containsKey("notificationsEnabled")) {
                user.setNotificationsEnabled(Boolean.TRUE.equals(body.get("notificationsEnabled")));
            } else if (body.containsKey("enableNotifications")) {
                user.setNotificationsEnabled(Boolean.TRUE.equals(body.get("enableNotifications")));
            }
        }

        userRepository.save(user);

        Map<String, Object> prefs = new LinkedHashMap<>();
        prefs.put("userId", user.getId());
        prefs.put("notificationsEnabled", user.isNotificationsEnabled());
        prefs.put("enableNotifications", user.isNotificationsEnabled());
        return ResponseEntity.ok(prefs);
    }
}
