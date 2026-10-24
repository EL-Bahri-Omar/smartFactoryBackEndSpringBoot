package com.smartfactory.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.*;
import com.smartfactory.entity.DeviceToken;
import com.smartfactory.repository.DeviceTokenRepository;
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
    private final ResourceLoader resourceLoader;

    @Value("${app.firebase.enabled:false}")
    private boolean firebaseEnabled;

    @Value("${app.firebase.credentials-path:${app.firebase.credentials-file:}}")
    private String firebaseCredentialsPath;

    private boolean firebaseInitialized = false;

    public PushNotificationService(DeviceTokenRepository deviceTokenRepository,
                                   ResourceLoader resourceLoader) {
        this.deviceTokenRepository = deviceTokenRepository;
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
     * Envoie une notification push vers tous les appareils actifs d'un utilisateur.
     */
    public void sendPushToUser(String userId, String title, String body, Map<String, String> data) {
        if (!StringUtils.hasText(userId)) {
            return;
        }

        List<DeviceToken> activeTokens = deviceTokenRepository.findByUserIdAndActiveTrue(userId.trim());
        if (activeTokens.isEmpty()) {
            // Chercher par userId direct au cas où active est null dans d'anciens documents
            activeTokens = deviceTokenRepository.findByUserId(userId.trim()).stream()
                    .filter(dt -> dt.isActive())
                    .toList();
        }

        if (activeTokens.isEmpty()) {
            log.debug("Aucun terminal push actif pour l'utilisateur [{}]", userId);
            return;
        }

        for (DeviceToken dt : activeTokens) {
            sendPushToDeviceToken(dt, title, body, data);
        }
    }

    /**
     * Envoie la notification push vers un terminal spécifique.
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

                // Options spécifiques selon deviceType
                if ("ANDROID".equalsIgnoreCase(deviceToken.getDeviceType())) {
                    messageBuilder.setAndroidConfig(AndroidConfig.builder()
                            .setPriority(AndroidConfig.Priority.HIGH)
                            .setNotification(AndroidNotification.builder()
                                    .setChannelId("default")
                                    .setSound("default")
                                    .build())
                            .build());
                } else if ("IOS".equalsIgnoreCase(deviceToken.getDeviceType())) {
                    messageBuilder.setApnsConfig(ApnsConfig.builder()
                            .setAps(Aps.builder()
                                    .setSound("default")
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
