package com.smartfactory.controller;

import com.smartfactory.entity.AppNotification;
import com.smartfactory.entity.DeviceToken;
import com.smartfactory.security.UserPrincipal;
import com.smartfactory.service.NotificationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Contrôleur REST pour l'enregistrement des terminaux FCM et la consultation des notifications (Sprint 5 - US40).
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    private String getUserId(UserPrincipal currentUser) {
        if (currentUser == null) return "ANONYMOUS";
        return currentUser.getId() != null ? currentUser.getId() : currentUser.getUsername();
    }

    private String getUserEmail(UserPrincipal currentUser) {
        if (currentUser == null) return "ANONYMOUS";
        return currentUser.getUsername();
    }

    /**
     * Enregistre un token FCM de terminal mobile ou web (US40).
     */
    @PostMapping("/tokens")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<DeviceToken> registerDeviceToken(
            @RequestBody DeviceToken request,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        String userId = getUserId(currentUser);
        DeviceToken token = notificationService.registerToken(userId, request.getToken(), request.getDeviceType(), request.getDeviceId());
        return new ResponseEntity<>(token, HttpStatus.CREATED);
    }

    /**
     * Supprime un token de terminal lors de la déconnexion via path variable.
     */
    @DeleteMapping("/tokens/{token}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> unregisterDeviceToken(@PathVariable String token) {
        notificationService.unregisterToken(token);
        return ResponseEntity.noContent().build();
    }

    /**
     * Supprime un token de terminal lors de la déconnexion via JSON body.
     */
    @DeleteMapping("/tokens")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> unregisterDeviceTokenBody(@RequestBody(required = false) Map<String, String> body) {
        if (body != null && body.containsKey("token")) {
            notificationService.unregisterToken(body.get("token"));
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Consulte les notifications in-app de l'utilisateur connecté (Section 7).
     * Les notifications sont strictement isolées par identité authentifiée.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<AppNotification>> getMyNotifications(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        String userId = getUserId(currentUser);
        String userEmail = getUserEmail(currentUser);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(notificationService.getUserNotifications(userId, userEmail, pageable));
    }

    /**
     * Marque une notification spécifique comme lue (Section 5).
     * Accepte PUT, PATCH et POST sur /{id}/read pour une compatibilité mobile/tablette totale.
     */
    @RequestMapping(value = "/{id}/read", method = {RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.POST})
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AppNotification> markAsRead(
            @PathVariable String id,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        String userId = getUserId(currentUser);
        String userEmail = getUserEmail(currentUser);
        return ResponseEntity.ok(notificationService.markAsRead(id, userId, userEmail));
    }

    /**
     * Alias de mise à jour de lecture d'une notification via /{id}.
     */
    @RequestMapping(value = "/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AppNotification> updateNotification(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        String userId = getUserId(currentUser);
        String userEmail = getUserEmail(currentUser);
        return ResponseEntity.ok(notificationService.markAsRead(id, userId, userEmail));
    }

    /**
     * Marque les notifications comme lues via l'endpoint contractuel /mark-read (Section 5).
     * Accepte un body avec id ou notificationId, ou sans body pour tout marquer comme lu.
     */
    @RequestMapping(value = "/mark-read", method = {RequestMethod.POST, RequestMethod.PUT})
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> markNotificationsAsRead(
            @RequestBody(required = false) Map<String, Object> requestBody,
            @RequestParam(required = false) String id,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        String userId = getUserId(currentUser);
        String userEmail = getUserEmail(currentUser);

        String targetId = id;
        if (!org.springframework.util.StringUtils.hasText(targetId) && requestBody != null) {
            if (requestBody.containsKey("id")) {
                targetId = String.valueOf(requestBody.get("id"));
            } else if (requestBody.containsKey("notificationId")) {
                targetId = String.valueOf(requestBody.get("notificationId"));
            }
        }

        if (org.springframework.util.StringUtils.hasText(targetId)) {
            AppNotification notif = notificationService.markAsRead(targetId.trim(), userId, userEmail);
            return ResponseEntity.ok(Map.of(
                    "message", "Notification marquée comme lue",
                    "notification", notif,
                    "unreadCount", notificationService.getUnreadCount(userId, userEmail)
            ));
        }

        // Si aucun ID spécifié, marquer l'ensemble des notifications de l'utilisateur comme lues
        long count = notificationService.markAllAsRead(userId, userEmail);
        return ResponseEntity.ok(Map.of(
                "message", count + " notifications marquées comme lues",
                "unreadCount", 0L
        ));
    }

    /**
     * Obtient le nombre de notifications non lues pour l'utilisateur connecté.
     */
    @GetMapping("/unread-count")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> getUnreadCount(@AuthenticationPrincipal UserPrincipal currentUser) {
        String userId = getUserId(currentUser);
        String userEmail = getUserEmail(currentUser);
        long unread = notificationService.getUnreadCount(userId, userEmail);
        return ResponseEntity.ok(Map.of("unreadCount", unread));
    }
}
