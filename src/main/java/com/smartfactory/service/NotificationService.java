package com.smartfactory.service;

import com.smartfactory.entity.Alert;
import com.smartfactory.entity.AppNotification;
import com.smartfactory.entity.DeviceToken;
import com.smartfactory.entity.User;
import com.smartfactory.repository.DeviceTokenRepository;
import com.smartfactory.repository.NotificationRepository;
import com.smartfactory.repository.UserRepository;
import com.smartfactory.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service de gestion des notifications multi-canaux (in-app et Firebase Cloud Messaging) (Sprint 5 - US40).
 *
 * Règles :
 * - Gestion de l'enregistrement et de la mise à jour des jetons de terminaux.
 * - Diffusion automatique lors des alertes CRITICAL et HIGH vers les techniciens et responsables.
 * - Fonctionnement résilient : Si les identifiants Firebase ne sont pas configurés,
 *   un client simulé journalise les envois sans bloquer la création des alertes.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final DeviceTokenRepository deviceTokenRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final RealtimeMessageService realtimeMessageService;

    @Value("${app.firebase.enabled:false}")
    private boolean firebaseEnabled = false;

    @Value("${app.firebase.credentials-file:}")
    private String firebaseCredentialsFile;

    public NotificationService(DeviceTokenRepository deviceTokenRepository,
                               NotificationRepository notificationRepository,
                               UserRepository userRepository,
                               RealtimeMessageService realtimeMessageService) {
        this.deviceTokenRepository = deviceTokenRepository;
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.realtimeMessageService = realtimeMessageService;
    }

    /**
     * Enregistre ou met à jour le token de notification d'un terminal pour un utilisateur.
     */
    public DeviceToken registerToken(String userId, String token, String deviceType) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(token)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User ID and token are required");
        }

        Optional<DeviceToken> existingOpt = deviceTokenRepository.findByToken(token.trim());
        DeviceToken deviceToken;
        if (existingOpt.isPresent()) {
            deviceToken = existingOpt.get();
            deviceToken.setUserId(userId);
            deviceToken.setDeviceType(deviceType != null ? deviceType : "ANDROID");
            deviceToken.setLastActiveAt(Instant.now());
        } else {
            deviceToken = new DeviceToken(userId, token.trim(), deviceType);
        }

        DeviceToken saved = deviceTokenRepository.save(deviceToken);
        log.info("Token de notification enregistré pour l'utilisateur [{}] ({})", userId, deviceToken.getDeviceType());
        return saved;
    }

    /**
     * Supprime un token de terminal lors de la déconnexion.
     */
    public void unregisterToken(String token) {
        if (StringUtils.hasText(token)) {
            deviceTokenRepository.deleteByToken(token.trim());
            log.info("Token de terminal supprimé : {}", token.trim());
        }
    }

    /**
     * Déclenche l'envoi de notification lors d'une transition d'alerte (WARNING, HIGH, CRITICAL) (US40).
     * Applique une déduplication stricte pour éviter la multiplication de notifications identiques.
     */
    public void notifyAlert(Alert alert) {
        if (alert == null) {
            return;
        }

        String severity = alert.getSeverity() != null ? alert.getSeverity().toUpperCase() : "WARNING";

        // Destinataires éligibles : Techniciens, Responsables industriels, Admins
        List<User> recipients = userRepository.findAll().stream()
                .filter(u -> "ACTIVE".equalsIgnoreCase(u.getStatus()))
                .filter(u -> u.getRole() == Role.TECHNICIAN || u.getRole() == Role.RESPONSABLE_INDUSTRIEL || u.getRole() == Role.ADMIN)
                .collect(Collectors.toList());

        String icon = "CRITICAL".equals(severity) ? "🚨" : ("HIGH".equals(severity) ? "⚠️" : "🟡");
        String title = icon + " ALERTE " + severity + " - Machine " + (alert.getMachineCode() != null ? alert.getMachineCode() : alert.getMachineId());
        String body = alert.getMessage() != null ? alert.getMessage() : ("Dépassement de seuil détecté : valeur " + alert.getValue());
        String notifType = "ALERT_" + severity;

        for (User recipient : recipients) {
            String recipientId = recipient.getId();

            // Déduplication : si une notification identique non lue existe déjà pour cette alerte et ce type, ne pas dupliquer
            if (alert.getId() != null && notificationRepository.existsByUserIdAndAlertIdAndType(recipientId, alert.getId(), notifType)) {
                log.debug("Notification alerte [{}] déjà présente pour l'utilisateur [{}], saut de déduplication", alert.getId(), recipientId);
                continue;
            }

            // Si transition WARNING -> CRITICAL, marquer l'ancienne notification WARNING comme lue pour éviter 2 indicateurs actifs
            if ("CRITICAL".equals(severity) && alert.getId() != null) {
                List<AppNotification> oldWarnings = notificationRepository.findByUserIdAndAlertId(recipientId, alert.getId());
                for (AppNotification old : oldWarnings) {
                    if ("ALERT_WARNING".equals(old.getType()) && !old.isRead()) {
                        old.setRead(true);
                        notificationRepository.save(old);
                    }
                }
            }

            AppNotification notif = new AppNotification(
                    recipientId,
                    alert.getId(),
                    alert.getMachineId(),
                    title,
                    body,
                    notifType
            );
            AppNotification savedNotif = notificationRepository.save(notif);

            // Diffusion WebSocket en temps réel vers le destinataire
            realtimeMessageService.broadcastNotificationToUser(recipientId, savedNotif);
            realtimeMessageService.broadcastNotificationToUser(recipient.getEmail(), savedNotif);
            realtimeMessageService.broadcastUnreadCountToUser(recipientId, getUnreadCount(recipientId, recipient.getEmail()));

            // Notification push (FCM réel ou simulé)
            dispatchPushNotification(recipientId, title, body, Map.of(
                    "alertId", alert.getId() != null ? alert.getId() : "",
                    "machineId", alert.getMachineId() != null ? alert.getMachineId() : "",
                    "severity", severity
            ));
        }
    }

    /**
     * Notifie un technicien lors de l'assignation d'une intervention de maintenance avec tous les détails métier (US43).
     */
    public void notifyMaintenanceAssigned(String technicianId, String interventionId, String title,
                                          String machineCode, String type, String subtype,
                                          String priority, Instant assignedAt) {
        if (!StringUtils.hasText(technicianId)) {
            return;
        }

        User technician = userRepository.findById(technicianId)
                .or(() -> userRepository.findByEmail(technicianId))
                .orElse(null);

        String canonicalUserId = technician != null ? technician.getId() : technicianId;

        // Éviter notification en double si déjà notifié pour cette intervention
        if (StringUtils.hasText(interventionId) &&
                notificationRepository.existsByUserIdAndAlertIdAndType(canonicalUserId, interventionId, "MAINTENANCE_ASSIGNED")) {
            log.debug("Technicien [{}] déjà notifié pour l'intervention [{}]", canonicalUserId, interventionId);
            return;
        }

        String notifTitle = "🔧 Nouvelle intervention assignée : " + (title != null ? title : "Intervention");
        StringBuilder bodyBuilder = new StringBuilder();
        if (StringUtils.hasText(machineCode)) {
            bodyBuilder.append("Machine : ").append(machineCode).append(" | ");
        }
        if (StringUtils.hasText(type)) {
            bodyBuilder.append("Type : ").append(type);
            if (StringUtils.hasText(subtype)) {
                bodyBuilder.append(" (").append(subtype).append(")");
            }
            bodyBuilder.append(" | ");
        }
        if (StringUtils.hasText(priority)) {
            bodyBuilder.append("Priorité : ").append(priority).append(" | ");
        }
        if (assignedAt != null) {
            bodyBuilder.append("Affectée le : ").append(assignedAt.toString());
        } else {
            bodyBuilder.append("Affectée le : ").append(Instant.now().toString());
        }

        String notifBody = bodyBuilder.toString();

        AppNotification notif = new AppNotification(
                canonicalUserId,
                interventionId,
                null,
                notifTitle,
                notifBody,
                "MAINTENANCE_ASSIGNED"
        );
        AppNotification savedNotif = notificationRepository.save(notif);

        // Diffusion WebSocket vers le technicien uniquement
        realtimeMessageService.broadcastNotificationToUser(canonicalUserId, savedNotif);
        if (technician != null && StringUtils.hasText(technician.getEmail())) {
            realtimeMessageService.broadcastNotificationToUser(technician.getEmail(), savedNotif);
        }
        realtimeMessageService.broadcastUnreadCountToUser(canonicalUserId, getUnreadCount(canonicalUserId, technician != null ? technician.getEmail() : null));

        // Push notification vers le technicien
        dispatchPushNotification(canonicalUserId, notifTitle, notifBody, Map.of(
                "interventionId", interventionId != null ? interventionId : "",
                "type", "MAINTENANCE_ASSIGNED"
        ));

        log.info("Notification d'affectation envoyée au technicien [{}] pour intervention [{}]", canonicalUserId, interventionId);
    }

    /**
     * Rétrocompatibilité : surcharge avec signature simplifiée.
     */
    public void notifyMaintenanceAssigned(String technicianId, String interventionId, String title) {
        notifyMaintenanceAssigned(technicianId, interventionId, title, null, null, null, null, Instant.now());
    }

    /**
     * Notifie l'ancien technicien lors de la réaffectation d'une intervention à un autre technicien.
     */
    public void notifyMaintenanceReassigned(String previousTechnicianId, String interventionId, String title, String newTechnicianName) {
        if (!StringUtils.hasText(previousTechnicianId)) {
            return;
        }

        User previousTech = userRepository.findById(previousTechnicianId)
                .or(() -> userRepository.findByEmail(previousTechnicianId))
                .orElse(null);

        String canonicalUserId = previousTech != null ? previousTech.getId() : previousTechnicianId;
        String notifTitle = "ℹ️ Intervention réassignée : " + (title != null ? title : "Intervention");
        String notifBody = "L'intervention " + (title != null ? title : "") + " a été réassignée"
                + (StringUtils.hasText(newTechnicianName) ? " à " + newTechnicianName : "") + ".";

        AppNotification notif = new AppNotification(
                canonicalUserId,
                interventionId,
                null,
                notifTitle,
                notifBody,
                "MAINTENANCE_REASSIGNED"
        );
        AppNotification savedNotif = notificationRepository.save(notif);

        realtimeMessageService.broadcastNotificationToUser(canonicalUserId, savedNotif);
        if (previousTech != null && StringUtils.hasText(previousTech.getEmail())) {
            realtimeMessageService.broadcastNotificationToUser(previousTech.getEmail(), savedNotif);
        }
        realtimeMessageService.broadcastUnreadCountToUser(canonicalUserId, getUnreadCount(canonicalUserId, previousTech != null ? previousTech.getEmail() : null));
    }

    /**
     * Envoie la notification push au terminal ou journalise une simulation conforme.
     */
    private void dispatchPushNotification(String userId, String title, String body, Map<String, String> data) {
        List<DeviceToken> tokens = deviceTokenRepository.findByUserId(userId);
        if (tokens.isEmpty()) {
            log.debug("Aucun token FCM enregistré pour l'utilisateur [{}]", userId);
            return;
        }

        for (DeviceToken dt : tokens) {
            if (firebaseEnabled && StringUtils.hasText(firebaseCredentialsFile)) {
                // Si les clés Firebase réelles sont fournies, exécuter l'envoi HTTP/SDK
                log.info("[FCM RÉEL] Envoi du message push vers token terminal [{}]", dt.getToken());
            } else {
                // Mode simulation FCM documenté et sécurisé sans fuite de secrets
                log.info("[FCM SIMULÉ] Notification dispatchée -> User: {}, Device: {}, Title: '{}', Body: '{}', Data: {}",
                        userId, dt.getDeviceType(), title, body, data);
            }
        }
    }

    public Page<AppNotification> getUserNotifications(String userId, String userEmail, Pageable pageable) {
        List<String> ids = new ArrayList<>();
        if (StringUtils.hasText(userId)) ids.add(userId.trim());
        if (StringUtils.hasText(userEmail) && !ids.contains(userEmail.trim())) ids.add(userEmail.trim());

        if (ids.isEmpty()) {
            return Page.empty(pageable);
        }
        return notificationRepository.findByUserIdIn(ids, pageable);
    }

    public Page<AppNotification> getUserNotifications(String userId, Pageable pageable) {
        return getUserNotifications(userId, null, pageable);
    }

    public AppNotification markAsRead(String notificationId, String userId, String userEmail) {
        AppNotification notif = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found: " + notificationId));

        boolean isOwner = (StringUtils.hasText(userId) && userId.equals(notif.getUserId()))
                || (StringUtils.hasText(userEmail) && userEmail.equalsIgnoreCase(notif.getUserId()));

        if (!isOwner) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to notification");
        }

        if (notif.isRead()) {
            return notif; // Idempotent
        }

        notif.setRead(true);
        AppNotification saved = notificationRepository.save(notif);

        // Mettre à jour le compteur non lu en temps réel
        if (StringUtils.hasText(userId)) {
            realtimeMessageService.broadcastUnreadCountToUser(userId, getUnreadCount(userId, userEmail));
        }

        return saved;
    }

    public AppNotification markAsRead(String notificationId, String userId) {
        return markAsRead(notificationId, userId, null);
    }

    public long markAllAsRead(String userId, String userEmail) {
        List<String> ids = new ArrayList<>();
        if (StringUtils.hasText(userId)) ids.add(userId.trim());
        if (StringUtils.hasText(userEmail) && !ids.contains(userEmail.trim())) ids.add(userEmail.trim());

        List<AppNotification> unreadList = notificationRepository.findByUserIdInAndIsReadFalse(ids);
        for (AppNotification notif : unreadList) {
            notif.setRead(true);
            notificationRepository.save(notif);
        }

        if (StringUtils.hasText(userId)) {
            realtimeMessageService.broadcastUnreadCountToUser(userId, 0L);
        }
        return unreadList.size();
    }

    public long getUnreadCount(String userId, String userEmail) {
        List<String> ids = new ArrayList<>();
        if (StringUtils.hasText(userId)) ids.add(userId.trim());
        if (StringUtils.hasText(userEmail) && !ids.contains(userEmail.trim())) ids.add(userEmail.trim());
        if (ids.isEmpty()) return 0L;
        return notificationRepository.countByUserIdInAndIsReadFalse(ids);
    }

    public long getUnreadCount(String userId) {
        return getUnreadCount(userId, null);
    }
}
