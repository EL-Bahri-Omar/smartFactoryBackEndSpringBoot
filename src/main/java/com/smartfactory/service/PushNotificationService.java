package com.smartfactory.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.*;
import com.smartfactory.entity.DeviceToken;
import com.smartfactory.entity.User;
import com.smartfactory.repository.DeviceTokenRepository;
import com.smartfactory.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Service découplé d'envoi des notifications push via Firebase Cloud Messaging (FCM).
 *
 * Fonctionnalités :
 * - Initialisation sécurisée du SDK Firebase Admin si les credentials sont configurés.
 * - Mode simulation résilient si Firebase n'est pas activé ou absent (pas de blocage métier).
 * - Multi-appareils : envoi à l'ensemble des terminaux actifs de l'utilisateur.
 * - Désactivation automatique des jetons obsolètes ou invalides (UNREGISTERED / INVALID_ARGUMENT).
 * - Aucune exposition de clés privées, secrets ou jetons sensibles dans les logs ou les réponses API.
 */
@Service
public class PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);

    private final DeviceTokenRepository deviceTokenRepository;
    private final UserRepository userRepository;
    private final ResourceLoader resourceLoader;

    /**
     * Cache d'idempotence mémoire éphémère pour éviter tout envoi en double
     * d'une même notification logique à un utilisateur dans une fenêtre de 60 secondes.
     */
    private final Map<String, Instant> sentEventsCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Value("${app.firebase.enabled:false}")
    private boolean firebaseEnabled;

    @Value("${app.firebase.credentials-path:${app.firebase.credentials-file:}}")
    private String firebaseCredentialsPath;

    private boolean firebaseInitialized = false;

    public PushNotificationService(DeviceTokenRepository deviceTokenRepository,
                                   UserRepository userRepository,
                                   ResourceLoader resourceLoader) {
        this.deviceTokenRepository = deviceTokenRepository;
        this.userRepository = userRepository;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    public void initFirebase() {
        if (!firebaseEnabled) {
            log.info("Push FCM : Mode simulation activé (app.firebase.enabled = false).");
            return;
        }

        String rawPath = firebaseCredentialsPath != null ? firebaseCredentialsPath.trim() : "";
        if (!StringUtils.hasText(rawPath)) {
            log.warn("Push FCM : app.firebase.enabled=true mais 'app.firebase.credentials-path' / 'credentials-file' est vide. Bascule en mode simulation.");
            return;
        }

        try {
            Resource resource = resourceLoader.getResource(rawPath);
            if (!resource.exists()) {
                if (!rawPath.startsWith("classpath:") && !rawPath.startsWith("file:")) {
                    resource = resourceLoader.getResource("classpath:" + rawPath);
                    if (!resource.exists()) {
                        resource = resourceLoader.getResource("file:" + rawPath);
                    }
                }
            }

            if (!resource.exists()) {
                log.warn("Push FCM : Fichier de credentials Firebase introuvable à l'emplacement [{}]. Bascule en mode simulation.",
                        sanitizePath(rawPath));
                return;
            }

            try (InputStream is = resource.getInputStream()) {
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(is))
                        .build();

                if (FirebaseApp.getApps().isEmpty()) {
                    FirebaseApp.initializeApp(options);
                }
                firebaseInitialized = true;
                log.info("Push FCM : Firebase Admin SDK initialisé avec succès.");
            }
        } catch (Exception ex) {
            log.error("Push FCM : Impossible d'initialiser Firebase Admin SDK ({}) - Fonctionnement en mode simulation.", ex.getMessage());
            firebaseInitialized = false;
        }
    }

    /**
     * Envoie une notification push vers tous les appareils actifs d'un utilisateur
     * après vérification stricte de ses préférences et déduplication idempotente (Sections 4 & 5).
     */
    public void sendPushToUser(String userId, String title, String body, Map<String, String> data) {
        if (!StringUtils.hasText(userId)) {
            return;
        }

        String cleanUserId = userId.trim();

        // 1. Respect strict des préférences utilisateur (Section 5)
        User recipientUser = userRepository.findById(cleanUserId)
                .or(() -> userRepository.findByEmail(cleanUserId))
                .orElse(null);

        if (recipientUser != null && !recipientUser.isNotificationsEnabled()) {
            log.info("[PUSH DÉSACTIVÉ] L'utilisateur [{}] a désactivé les notifications dans ses préférences. Envoi push ignoré.", cleanUserId);
            return;
        }

        // 2. Déduplication et idempotence au niveau applicatif (Section 4)
        String eventKey = data != null ? data.getOrDefault("eventId",
                data.getOrDefault("alertId",
                        data.getOrDefault("interventionId",
                                data.getOrDefault("type", title != null ? title : "default_event")))) : "default_event";
        String idempotencyKey = cleanUserId + ":" + eventKey;

        Instant now = Instant.now();
        Instant lastSent = sentEventsCache.get(idempotencyKey);
        if (lastSent != null && ChronoUnit.SECONDS.between(lastSent, now) < 60) {
            log.debug("[PUSH DÉDUPLIQUÉ] Notification [{}] déjà transmise à l'utilisateur [{}] il y a moins de 60s. Émission ignorée.",
                    eventKey, cleanUserId);
            return;
        }
        sentEventsCache.put(idempotencyKey, now);

        // Nettoyage périodique du cache d'idempotence si nécessaire
        if (sentEventsCache.size() > 5000) {
            sentEventsCache.entrySet().removeIf(entry -> ChronoUnit.MINUTES.between(entry.getValue(), now) > 5);
        }

        // 3. Recherche et déduplication des jetons actifs du destinataire
        List<DeviceToken> activeTokens = deviceTokenRepository.findByUserIdAndActiveTrue(cleanUserId);
        if (activeTokens.isEmpty()) {
            // Chercher par userId direct au cas où active est null dans d'anciens documents
            activeTokens = deviceTokenRepository.findByUserId(cleanUserId).stream()
                    .filter(DeviceToken::isActive)
                    .toList();
        }

        if (activeTokens.isEmpty()) {
            log.debug("Aucun terminal push actif pour l'utilisateur [{}]", cleanUserId);
            return;
        }

        // Dédupliquer les jetons uniques pour éviter l'envoi répété vers le même terminal physique
        Set<String> uniqueTokens = new HashSet<>();
        for (DeviceToken dt : activeTokens) {
            if (StringUtils.hasText(dt.getToken()) && uniqueTokens.add(dt.getToken().trim())) {
                sendPushToDeviceToken(dt, title, body, data);
            }
        }
    }

    public void sendPushNotification(String userId, String title, String body, Map<String, String> data) {
        sendPushToUser(userId, title, body, data);
    }

    /**
     * Envoie la notification push vers un terminal spécifique avec tag de regroupement
     * Android/iOS afin d'éviter l'affichage de notifications en double (Section 4).
     */
    public boolean sendPushToDeviceToken(DeviceToken deviceToken, String title, String body, Map<String, String> data) {
        if (deviceToken == null || !StringUtils.hasText(deviceToken.getToken())) {
            return false;
        }

        String rawToken = deviceToken.getToken().trim();
        Map<String, String> sanitizedData = new HashMap<>();
        if (data != null) {
            data.forEach((k, v) -> {
                if (k != null && v != null) {
                    sanitizedData.put(k, String.valueOf(v));
                }
            });
        }

        String eventTag = data != null ? data.getOrDefault("eventId",
                data.getOrDefault("alertId",
                        data.getOrDefault("interventionId",
                                data.getOrDefault("type", "smartfactory_event")))) : "smartfactory_event";

        if (firebaseInitialized) {
            try {
                Notification notification = Notification.builder()
                        .setTitle(title != null ? title : "SmartFactory")
                        .setBody(body != null ? body : "")
                        .build();

                Message.Builder messageBuilder = Message.builder()
                        .setToken(rawToken)
                        .setNotification(notification)
                        .putAllData(sanitizedData);

                // Options spécifiques selon deviceType avec collapseKey et Tag uniques
                if ("ANDROID".equalsIgnoreCase(deviceToken.getDeviceType())) {
                    messageBuilder.setAndroidConfig(AndroidConfig.builder()
                            .setPriority(AndroidConfig.Priority.HIGH)
                            .setCollapseKey(eventTag)
                            .setNotification(AndroidNotification.builder()
                                    .setChannelId("default")
                                    .setSound("default")
                                    .setTag(eventTag)
                                    .build())
                            .build());
                } else if ("IOS".equalsIgnoreCase(deviceToken.getDeviceType())) {
                    messageBuilder.setApnsConfig(ApnsConfig.builder()
                            .setAps(Aps.builder()
                                    .setSound("default")
                                    .setThreadId(eventTag)
                                    .build())
                            .build());
                }

                String response = FirebaseMessaging.getInstance().send(messageBuilder.build());
                log.info("[FCM ENVOI SUCCÈS] Message envoyé au terminal (id: {}) - Réponse: {}",
                        deviceToken.getId(), response);

                deviceToken.setLastActiveAt(Instant.now());
                deviceTokenRepository.save(deviceToken);
                return true;

            } catch (FirebaseMessagingException fme) {
                log.warn("[FCM ERREUR] Échec de distribution pour le terminal [id: {}] : {}",
                        deviceToken.getId(), fme.getMessagingErrorCode());

                if (isTokenInvalid(fme)) {
                    log.info("Désactivation du jeton terminal invalide ou non enregistré [id: {}]", deviceToken.getId());
                    deviceToken.setActive(false);
                    deviceTokenRepository.save(deviceToken);
                }
                return false;
            } catch (Exception ex) {
                log.error("[FCM ERREUR INATTENDUE] Échec d'envoi non bloquant : {}", ex.getMessage());
                return false;
            }
        } else {
            // Mode simulation transparent et sécurisé sans exposition de secrets
            log.info("[FCM SIMULÉ] Push dispatché -> User: [{}], Device: [{}], Title: '{}', Body: '{}', Data: {}",
                    deviceToken.getUserId(), deviceToken.getDeviceType(), title, body, sanitizedData);
            return true;
        }
    }

    private boolean isTokenInvalid(FirebaseMessagingException fme) {
        MessagingErrorCode code = fme.getMessagingErrorCode();
        return code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT;
    }

    private String sanitizePath(String path) {
        if (!StringUtils.hasText(path)) return "";
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    public boolean isFirebaseInitialized() {
        return firebaseInitialized;
    }
}
