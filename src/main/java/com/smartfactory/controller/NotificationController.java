package com.smartfactory.controller;

import com.smartfactory.dto.DeviceTokenRequest;
import com.smartfactory.entity.AppNotification;
import com.smartfactory.entity.DeviceToken;
import com.smartfactory.security.UserPrincipal;
import com.smartfactory.service.NotificationService;
import jakarta.validation.Valid;
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

    /**
     * Enregistre un token FCM de terminal mobile ou web (US40).
     */
    @PostMapping("/tokens")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<DeviceToken> registerDeviceToken(
            @Valid @RequestBody DeviceTokenRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        String userId = currentUser != null ? currentUser.getUsername() : "ANONYMOUS";
        DeviceToken token = notificationService.registerToken(userId, request.getToken(), request.getDeviceType());
        return new ResponseEntity<>(token, HttpStatus.CREATED);
    }

    /**
     * Supprime un token de terminal lors de la déconnexion.
     */
    @DeleteMapping("/tokens/{token}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> unregisterDeviceToken(@PathVariable String token) {
        notificationService.unregisterToken(token);
        return ResponseEntity.noContent().build();
    }

    /**
     * Consulte les notifications in-app de l'utilisateur connecté.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<AppNotification>> getMyNotifications(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        String userId = currentUser != null ? currentUser.getUsername() : "ANONYMOUS";
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(notificationService.getUserNotifications(userId, pageable));
    }

    /**
     * Marque une notification comme lue.
     */
    @PutMapping("/{id}/read")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AppNotification> markAsRead(
            @PathVariable String id,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        String userId = currentUser != null ? currentUser.getUsername() : "ANONYMOUS";
        return ResponseEntity.ok(notificationService.markAsRead(id, userId));
    }

    /**
     * Obtient le nombre de notifications non lues.
     */
    @GetMapping("/unread-count")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> getUnreadCount(@AuthenticationPrincipal UserPrincipal currentUser) {
        String userId = currentUser != null ? currentUser.getUsername() : "ANONYMOUS";
        long unread = notificationService.getUnreadCount(userId);
        return ResponseEntity.ok(Map.of("unreadCount", unread));
    }
}
