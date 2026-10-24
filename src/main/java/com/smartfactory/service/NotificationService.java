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
 * Service de gestion intégrale des notifications multi-canaux (in-app, WebSocket temps réel, et push FCM).
 *
 * Fonctionnalités couvertes :
 * - Stockage et cycle de vie des notifications persistées dans MongoDB.
 * - Gestion du champ strict interventionId pour les interventions de maintenance.
 * - Notifications d'alertes (WARNING, CRITICAL) dédupliquées sans spam.
 * - Notifications de groupes : ajout et retrait d'un opérateur avec notification push.
 * - Notifications d'interventions : modification par un technicien et complétion adressées au responsable créateur.
 * - Découplage complet de l'envoi push via PushNotificationService (mode simulation si FCM non configuré).
 * - Isolation stricte par utilisateur : consultation, comptage et marquage comme lu sécurisés.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final DeviceTokenRepository deviceTokenRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final RealtimeMessageService realtimeMessageService;
    private final PushNotificationService pushNotificationService;

    public NotificationService(DeviceTokenRepository deviceTokenRepository,
                               NotificationRepository notificationRepository,
                               UserRepository userRepository,
                               RealtimeMessageService realtimeMessageService,
                               PushNotificationService pushNotificationService) {
        this.deviceTokenRepository = deviceTokenRepository;
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.realtimeMessageService = realtimeMessageService;
        this.pushNotificationService = pushNotificationService;
    }

    /**
     * Enregistre ou met à jour le token de notification d'un terminal pour un utilisateur.
     */
    public DeviceToken registerToken(String userId, String token, String deviceType, String deviceId) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(token)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User ID and token are required");
        }

        String cleanToken = token.trim();
        Optional<DeviceToken> existingOpt = deviceTokenRepository.findByToken(cleanToken);
        DeviceToken deviceToken;
        if (existingOpt.isPresent()) {
            deviceToken = existingOpt.get();
            deviceToken.setUserId(userId.trim());
            deviceToken.setDeviceType(deviceType != null ? deviceType : "ANDROID");
            if (StringUtils.hasText(deviceId)) {
                deviceToken.setDeviceId(deviceId.trim());
            }
            deviceToken.setActive(true);
            deviceToken.setLastActiveAt(Instant.now());
        } else {
            deviceToken = new DeviceToken(userId.trim(), cleanToken, deviceType, deviceId);
        }

        DeviceToken saved = deviceTokenRepository.save(deviceToken);
        log.info("Token de notification enregistré pour l'utilisateur [{}] ({}) [active={}]",
                userId, deviceToken.getDeviceType(), saved.isActive());
        return saved;
    }

    public DeviceToken registerToken(String userId, String token, String deviceType) {
        return registerToken(userId, token, deviceType, null);
    }

    public void unregisterToken(String token) {
        if (StringUtils.hasText(token)) {
            String cleanToken = token.trim();
            deviceTokenRepository.findByToken(cleanToken).ifPresent(dt -> {
                dt.setActive(false);
                deviceTokenRepository.save(dt);
                log.info("Token de terminal désactivé : {}", cleanToken);
            });
        }
    }

    /**
     * Déclenche l'envoi de notification lors d'une transition d'alerte (WARNING, CRITICAL).
     */
    public void notifyAlert(Alert alert) {
        if (alert == null) {
            return;
        }

        String severity = alert.getSeverity() != null ? alert.getSeverity().toUpperCase() : "WARNING";

        // Destinataires éligibles : Techniciens, Responsables industriels, Admins actifs
        List<User> recipients = userRepository.findAll().stream()
                .filter(u -> "ACTIVE".equalsIgnoreCase(u.getStatus()))
                .filter(u -> u.getRole() == Role.TECHNICIAN || u.getRole() == Role.RESPONSABLE_INDUSTRIEL || u.getRole() == Role.ADMIN)
                .collect(Collectors.toList());

        String icon = "CRITICAL".equals(severity) ? "🚨" : ("HIGH".equals(severity) ? "⚠️" : "🟡");
        String title = icon + " ALERTE " + severity + " - Machine " + (alert.getMachineCode() != null ? alert.getMachineCode() : alert.getMachineId());
        String body = alert.getMessage() != null ? alert.getMessage() : ("Dépassement de seuil détecté : valeur " + alert.getValue());
        String notifType = "ALERT_" + severity;

        Map<String, Object> dataPayload = new LinkedHashMap<>();
        dataPayload.put("alertId", alert.getId() != null ? alert.getId() : "");
        dataPayload.put("machineId", alert.getMachineId() != null ? alert.getMachineId() : "");
        dataPayload.put("severity", severity);
        dataPayload.put("screen", "AlertDetail");
        dataPayload.put("type", notifType);

        Map<String, String> pushData = new HashMap<>();
        dataPayload.forEach((k, v) -> pushData.put(k, String.valueOf(v)));

        for (User recipient : recipients) {
            String recipientId = recipient.getId();

            // Déduplication : si une notification identique non lue existe déjà pour cette alerte et ce type
            if (alert.getId() != null && notificationRepository.existsByUserIdAndAlertIdAndType(recipientId, alert.getId(), notifType)) {
                log.debug("Notification alerte [{}] déjà présente pour [{}], déduplication appliquée", alert.getId(), recipientId);
                continue;
            }

            // Si transition vers CRITICAL, marquer l'ancienne notification WARNING comme lue
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
                    null, // interventionId est null pour une alerte capteur
                    alert.getMachineId(),
                    title,
                    body,
                    notifType,
                    dataPayload
            );
            AppNotification savedNotif = notificationRepository.save(notif);

            // Diffusion WebSocket en temps réel vers le destinataire
            realtimeMessageService.broadcastNotificationToUser(recipientId, savedNotif);
            if (StringUtils.hasText(recipient.getEmail())) {
                realtimeMessageService.broadcastNotificationToUser(recipient.getEmail(), savedNotif);
            }
            realtimeMessageService.broadcastUnreadCountToUser(recipientId, getUnreadCount(recipientId, recipient.getEmail()));

            // Envoi push via PushNotificationService découplé
            pushNotificationService.sendPushToUser(recipientId, title, body, pushData);
        }
    }

    /**
     * Notifie un opérateur lors de son ajout à un groupe d'opérateurs (Exigence 4).
     */
    public void notifyOperatorAddedToGroup(String operatorId, String groupId, String groupName) {
        if (!StringUtils.hasText(operatorId)) {
            return;
        }

        User operator = userRepository.findById(operatorId)
                .or(() -> userRepository.findByEmail(operatorId))
                .orElse(null);

        String canonicalUserId = operator != null ? operator.getId() : operatorId;
        String displayName = StringUtils.hasText(groupName) ? groupName : groupId;

        String notifTitle = "👥 Affectation au groupe : " + displayName;
        String notifBody = "Vous avez été ajouté au groupe d'opérateurs \"" + displayName + "\".";
        String notifType = "GROUP_MEMBER_ADDED";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("groupId", groupId != null ? groupId : "");
        data.put("groupName", displayName);
        data.put("type", notifType);
        data.put("screen", "GroupDetail");

        AppNotification notif = new AppNotification(
                canonicalUserId,
                null, // alertId
                null, // interventionId
                null, // machineId
                notifTitle,
                notifBody,
                notifType,
                data
        );
        AppNotification savedNotif = notificationRepository.save(notif);

        realtimeMessageService.broadcastNotificationToUser(canonicalUserId, savedNotif);
        if (operator != null && StringUtils.hasText(operator.getEmail())) {
            realtimeMessageService.broadcastNotificationToUser(operator.getEmail(), savedNotif);
        }
        realtimeMessageService.broadcastUnreadCountToUser(canonicalUserId, getUnreadCount(canonicalUserId, operator != null ? operator.getEmail() : null));

        Map<String, String> pushData = new HashMap<>();
        data.forEach((k, v) -> pushData.put(k, String.valueOf(v)));
        pushNotificationService.sendPushToUser(canonicalUserId, notifTitle, notifBody, pushData);

        log.info("Notification d'ajout de groupe envoyée à l'opérateur [{}] pour le groupe [{}]", canonicalUserId, displayName);
    }

    /**
     * Notifie un opérateur lors de son retrait d'un groupe d'opérateurs (Exigence 4).
     */
    public void notifyOperatorRemovedFromGroup(String operatorId, String groupId, String groupName) {
        if (!StringUtils.hasText(operatorId)) {
            return;
        }

        User operator = userRepository.findById(operatorId)
                .or(() -> userRepository.findByEmail(operatorId))
                .orElse(null);

        String canonicalUserId = operator != null ? operator.getId() : operatorId;
        String displayName = StringUtils.hasText(groupName) ? groupName : groupId;

        String notifTitle = "👥 Retrait du groupe : " + displayName;
        String notifBody = "Vous avez été retiré du groupe d'opérateurs \"" + displayName + "\".";
        String notifType = "GROUP_MEMBER_REMOVED";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("groupId", groupId != null ? groupId : "");
        data.put("groupName", displayName);
        data.put("type", notifType);

        AppNotification notif = new AppNotification(
                canonicalUserId,
                null, // alertId
                null, // interventionId
                null, // machineId
                notifTitle,
                notifBody,
                notifType,
                data
        );
        AppNotification savedNotif = notificationRepository.save(notif);

        realtimeMessageService.broadcastNotificationToUser(canonicalUserId, savedNotif);
        if (operator != null && StringUtils.hasText(operator.getEmail())) {
            realtimeMessageService.broadcastNotificationToUser(operator.getEmail(), savedNotif);
        }
        realtimeMessageService.broadcastUnreadCountToUser(canonicalUserId, getUnreadCount(canonicalUserId, operator != null ? operator.getEmail() : null));

        Map<String, String> pushData = new HashMap<>();
        data.forEach((k, v) -> pushData.put(k, String.valueOf(v)));
        pushNotificationService.sendPushToUser(canonicalUserId, notifTitle, notifBody, pushData);

        log.info("Notification de retrait de groupe envoyée à l'opérateur [{}] pour le groupe [{}]", canonicalUserId, displayName);
    }

    /**
     * Notifie un technicien lors de son affectation à une intervention (Exigence 5 & 6).
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

        // Éviter notification en double si déjà notifié
        if (StringUtils.hasText(interventionId) &&
                notificationRepository.existsByUserIdAndInterventionIdAndType(canonicalUserId, interventionId, "MAINTENANCE_ASSIGNED")) {
            log.debug("Technicien [{}] déjà notifié pour intervention [{}]", canonicalUserId, interventionId);
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
        String notifType = "MAINTENANCE_ASSIGNED";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("interventionId", interventionId != null ? interventionId : "");
        data.put("machineCode", machineCode != null ? machineCode : "");
        data.put("type", notifType);
        data.put("screen", "InterventionDetail");

        AppNotification notif = new AppNotification(
                canonicalUserId,
                null, // alertId
                interventionId, // interventionId véridique
                null, // machineId
                notifTitle,
                notifBody,
                notifType,
                data
        );
        AppNotification savedNotif = notificationRepository.save(notif);

        realtimeMessageService.broadcastNotificationToUser(canonicalUserId, savedNotif);
        if (technician != null && StringUtils.hasText(technician.getEmail())) {
            realtimeMessageService.broadcastNotificationToUser(technician.getEmail(), savedNotif);
        }
        realtimeMessageService.broadcastUnreadCountToUser(canonicalUserId, getUnreadCount(canonicalUserId, technician != null ? technician.getEmail() : null));

        Map<String, String> pushData = new HashMap<>();
        data.forEach((k, v) -> pushData.put(k, String.valueOf(v)));
        pushNotificationService.sendPushToUser(canonicalUserId, notifTitle, notifBody, pushData);

        log.info("Notification d'affectation envoyée au technicien [{}] pour intervention [{}]", canonicalUserId, interventionId);
    }

    /**
     * Surcharge pour affectation à une liste de plusieurs techniciens (Exigence 6).
     */
    public void notifyMaintenanceAssigned(List<String> technicianIds, String interventionId, String title,
                                          String machineCode, String type, String subtype,
                                          String priority, Instant assignedAt) {
        if (technicianIds == null || technicianIds.isEmpty()) {
            return;
        }
        for (String techId : technicianIds) {
            notifyMaintenanceAssigned(techId, interventionId, title, machineCode, type, subtype, priority, assignedAt);
        }
    }

    public void notifyMaintenanceAssigned(String technicianId, String interventionId, String title) {
        notifyMaintenanceAssigned(technicianId, interventionId, title, null, null, null, null, Instant.now());
    }

    /**
     * Notifie un technicien lors de la réassignation ou du retrait d'une intervention.
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
        String notifType = "MAINTENANCE_REASSIGNED";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("interventionId", interventionId != null ? interventionId : "");
        data.put("type", notifType);
        data.put("screen", "InterventionDetail");

        AppNotification notif = new AppNotification(
                canonicalUserId,
                null,
                interventionId,
                null,
                notifTitle,
                notifBody,
                notifType,
                data
        );
        AppNotification savedNotif = notificationRepository.save(notif);

        realtimeMessageService.broadcastNotificationToUser(canonicalUserId, savedNotif);
        if (previousTech != null && StringUtils.hasText(previousTech.getEmail())) {
            realtimeMessageService.broadcastNotificationToUser(previousTech.getEmail(), savedNotif);
        }
        realtimeMessageService.broadcastUnreadCountToUser(canonicalUserId, getUnreadCount(canonicalUserId, previousTech != null ? previousTech.getEmail() : null));

        Map<String, String> pushData = new HashMap<>();
        data.forEach((k, v) -> pushData.put(k, String.valueOf(v)));
        pushNotificationService.sendPushToUser(canonicalUserId, notifTitle, notifBody, pushData);
    }

    /**
     * Notifie le responsable créateur lorsqu'un technicien modifie une intervention (Exigence 5).
     */
    public void notifyTechnicianModifiedIntervention(String creatorManagerId, String interventionId,
                                                    String title, String machineCode,
                                                    String technicianId, String technicianName,
                                                    String details) {
        if (!StringUtils.hasText(creatorManagerId)) {
            return;
        }

        // Si le technicien est lui-même l'auteur, ne pas s'auto-notifier
        if (creatorManagerId.equalsIgnoreCase(technicianId) || creatorManagerId.equalsIgnoreCase(technicianName)) {
            return;
        }

        User manager = userRepository.findById(creatorManagerId)
                .or(() -> userRepository.findByEmail(creatorManagerId))
                .orElse(null);

        String canonicalUserId = manager != null ? manager.getId() : creatorManagerId;
        String techDisplayName = StringUtils.hasText(technicianName) ? technicianName : (technicianId != null ? technicianId : "Un technicien");

        String notifTitle = "🛠️ Intervention modifiée par " + techDisplayName;
        String notifBody = "Le technicien " + techDisplayName + " a mis à jour l'intervention \""
                + (title != null ? title : interventionId) + "\""
                + (StringUtils.hasText(machineCode) ? " (machine " + machineCode + ")" : "")
                + (StringUtils.hasText(details) ? " : " + details : ".");
        String notifType = "MAINTENANCE_UPDATED";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("interventionId", interventionId != null ? interventionId : "");
        data.put("technicianId", technicianId != null ? technicianId : "");
        data.put("technicianName", techDisplayName);
        data.put("type", notifType);
        data.put("screen", "InterventionDetail");

        AppNotification notif = new AppNotification(
                canonicalUserId,
                null,
                interventionId,
                null,
                notifTitle,
                notifBody,
                notifType,
                data
        );
        AppNotification savedNotif = notificationRepository.save(notif);

        realtimeMessageService.broadcastNotificationToUser(canonicalUserId, savedNotif);
        if (manager != null && StringUtils.hasText(manager.getEmail())) {
            realtimeMessageService.broadcastNotificationToUser(manager.getEmail(), savedNotif);
        }
        realtimeMessageService.broadcastUnreadCountToUser(canonicalUserId, getUnreadCount(canonicalUserId, manager != null ? manager.getEmail() : null));

        Map<String, String> pushData = new HashMap<>();
        data.forEach((k, v) -> pushData.put(k, String.valueOf(v)));
        pushNotificationService.sendPushToUser(canonicalUserId, notifTitle, notifBody, pushData);

        log.info("Notification de modification d'intervention envoyée au responsable [{}] pour [{}] par [{}]",
                canonicalUserId, interventionId, techDisplayName);
    }

    /**
     * Notifie le responsable créateur lorsqu'un technicien termine une intervention (Exigence 5).
     */
    public void notifyTechnicianCompletedIntervention(String creatorManagerId, String interventionId,
                                                     String title, String machineCode,
                                                     String technicianId, String technicianName,
                                                     String closureNotes) {
        if (!StringUtils.hasText(creatorManagerId)) {
            return;
        }

        User manager = userRepository.findById(creatorManagerId)
                .or(() -> userRepository.findByEmail(creatorManagerId))
                .orElse(null);

        String canonicalUserId = manager != null ? manager.getId() : creatorManagerId;
        String techDisplayName = StringUtils.hasText(technicianName) ? technicianName : (technicianId != null ? technicianId : "Le technicien");

        String notifTitle = "✅ Intervention terminée : " + (title != null ? title : "Intervention");
        String notifBody = "L'intervention \"" + (title != null ? title : interventionId) + "\" a été terminée par "
                + techDisplayName
                + (StringUtils.hasText(machineCode) ? " (machine " + machineCode + ")." : ".")
                + (StringUtils.hasText(closureNotes) ? " Notes : " + closureNotes : "");
        String notifType = "MAINTENANCE_COMPLETED";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("interventionId", interventionId != null ? interventionId : "");
        data.put("technicianId", technicianId != null ? technicianId : "");
        data.put("technicianName", techDisplayName);
        data.put("type", notifType);
        data.put("screen", "InterventionDetail");

        AppNotification notif = new AppNotification(
                canonicalUserId,
                null,
                interventionId,
                null,
                notifTitle,
                notifBody,
                notifType,
                data
        );
        AppNotification savedNotif = notificationRepository.save(notif);

        realtimeMessageService.broadcastNotificationToUser(canonicalUserId, savedNotif);
        if (manager != null && StringUtils.hasText(manager.getEmail())) {
            realtimeMessageService.broadcastNotificationToUser(manager.getEmail(), savedNotif);
        }
        realtimeMessageService.broadcastUnreadCountToUser(canonicalUserId, getUnreadCount(canonicalUserId, manager != null ? manager.getEmail() : null));

        Map<String, String> pushData = new HashMap<>();
        data.forEach((k, v) -> pushData.put(k, String.valueOf(v)));
        pushNotificationService.sendPushToUser(canonicalUserId, notifTitle, notifBody, pushData);

        log.info("Notification de complétion d'intervention envoyée au responsable [{}] pour [{}] par [{}]",
                canonicalUserId, interventionId, techDisplayName);
    }

    /**
     * Récupère les notifications paginées de l'utilisateur authentifié (Exigence 3).
     */
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

    /**
     * Marque une notification comme lue en garantissant l'isolation des droits (Exigence 3).
     */
    public AppNotification markAsRead(String notificationId, String userId, String userEmail) {
        AppNotification notif = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification introuvable : " + notificationId));

        boolean isOwner = (StringUtils.hasText(userId) && userId.equals(notif.getUserId()))
                || (StringUtils.hasText(userEmail) && userEmail.equalsIgnoreCase(notif.getUserId()));

        if (!isOwner) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé à cette notification");
        }

        if (notif.isRead()) {
            return notif; // Idempotent
        }

        notif.setRead(true);
        AppNotification saved = notificationRepository.save(notif);

        // Mettre à jour le compteur non lu en temps réel via WebSocket
        if (StringUtils.hasText(userId)) {
            realtimeMessageService.broadcastUnreadCountToUser(userId, getUnreadCount(userId, userEmail));
        }
        if (StringUtils.hasText(userEmail)) {
            realtimeMessageService.broadcastUnreadCountToUser(userEmail, getUnreadCount(userId, userEmail));
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
        if (StringUtils.hasText(userEmail)) {
            realtimeMessageService.broadcastUnreadCountToUser(userEmail, 0L);
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
