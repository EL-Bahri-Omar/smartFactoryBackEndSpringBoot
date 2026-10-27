# Documentation Complète du Backend - SmartFactory

Plateforme de Supervision et de Maintenance Prédictive pour Machines Industrielles IoT.

---

## Sommaire
- [A. Architecture Générale](#a-architecture-générale)
- [B. Inventaire Complet des Fichiers Source](#b-inventaire-complet-des-fichiers-source)
- [C. Fonctionnement du Système de Permissions et Privilèges](#c-fonctionnement-du-système-de-permissions-et-privilèges)
- [D. Fonctionnement du Stockage et de l'Évaluation des Seuils](#d-fonctionnement-du-stockage-et-de-lévaluation-des-seuils)
- [E. Référentiel Complet des Endpoints API REST](#e-référentiel-complet-des-endpoints-api-rest)
- [F. Schéma de la Base de Données et Migration](#f-schéma-de-la-base-de-données-et-migration)
- [G. Guide d'Installation, Déploiement et Vérification](#g-guide-dinstallation-déploiement-et-vérification)
- [H. Limites, Compatibilité et Synthèse](#h-limites-compatibilité-et-synthèse)

---

## A. Architecture Générale

### 1. Présentation de l'Architecture
Le backend de **SmartFactory** est un service distribué conçu selon le style architectural **en couches (Layered Architecture)** et orienté événements (Event-Driven) pour l'ingestion temps réel de données IoT industrielles.

Il orchestre :
1. **L'ingestion télémétrique MQTT** : Réception à haute fréquence des mesures de capteurs physiques ou simulés via Eclipse Paho et Spring Integration.
2. **Le traitement et la détection d'anomalies** : Validation des schémas JSON, filtrage des déduplications, enrichissement des métadonnées, évaluation dynamique des seuils physiques configurables et génération d'alertes en temps réel.
3. **La diffusion temps réel WebSockets / STOMP** : Transmission instantanée des mesures et alertes aux tableaux de bord web et applications mobiles (Expo / React Native).
4. **Les API RESTful sécurisées** : Opérations CRUD pour la gestion des machines, zones, groupes d'opérateurs, comptes utilisateurs, seuils et historique d'ingestion.
5. **La sécurité à granularité fine (RBAC & ABAC hybride)** : Authentification JWT apatride (stateless) couplée à un système d'autorisations fondé sur des privilèges personnalisables individuellement par utilisateur.

### 2. Technologies et Dépendances Principales
- **Framework applicatif** : Java 17+ (testé et validé sous Java 25 LTS), Spring Boot `3.4.1`.
- **Couche Web & Sécurité** : Spring Web MVC, Spring Security 6, JJWT (`io.jsonwebtoken 0.12.6`).
- **Persistance** : Spring Data MongoDB (`spring-boot-starter-data-mongodb`) connecté à une base MongoDB locale ou distribuée.
- **Messagerie IoT & Temps Réel** : Spring Integration MQTT, Eclipse Paho MQTTv3 Client `1.2.5`, Spring WebSocket (STOMP over SockJS).
- **Communication par courriel & OTP** : Spring Boot Starter Mail (SMTP Gmail avec chiffrement STARTTLS).
- **Validation & Utilitaires** : Jakarta Validation (`hibernate-validator`), Jackson Databind, BCrypt Password Encoder.
- **Tests** : JUnit 5 (Jupiter), Mockito, Spring Boot Test, Spring Security Test, MockMvc, AssertJ.

### 3. Organisation des Packages
```
com.smartfactory
├── SmartFactoryApplication.java     # Point d'entrée Spring Boot et activation MongoAuditing
├── config/                         # Configuration Spring (Sécurité, MQTT, WebSocket, Bases hebdo)
├── controller/                     # Contrôleurs REST exposant les endpoints HTTP
├── entity/                         # Modèles de domaine persistés dans MongoDB (DTO purgés, entités directes)
├── repository/                     # Interfaces Spring Data MongoRepository
├── security/                       # Filtres JWT, gestionnaire de privilèges, UserPrincipal
└── service/                        # Logique métier, supervision industrielle, détection d'anomalies
```

> **Note d'Architecture — Suppression Intégrale de la Couche DTO** :  
> L'ensemble des 18 classes DTO intermédiaires du package `com.smartfactory.dto` a été entièrement supprimé. Les contrôleurs et services utilisent directement les entités du domaine (`com.smartfactory.entity`) et des structures de données standardisées (`Map<String, Object>`), simplifiant la maintenance tout en garantissant une rétrocompatibilité JSON rigoureusement identique pour les clients REST.

### 4. Flux Général des Données
```
[Simulateur IoT / Capteurs ESP32]
        │
        │ MQTT Publish: factory/machines/{machineCode}/{sensorType}
        ▼
[Broker MQTT (Mosquitto)]
        │
        ▼
[Spring Integration MQTT (MqttConfig)]
        │
        ▼
[SensorReadingService] ◄─── O(1) Cache ─── [MachineThresholdService] ◄─── [MongoDB: machine_thresholds]
        │                                         ▲
        ├─ Validation format, machine, unit, ts    │ (Mise à jour à chaud via REST)
        ├─ Déduplication                           │
        ├─ Persistance reading (sensor_readings)  [MachineThresholdController]
        ├─ Évaluation seuils dynamiques           ▲ (Autorisation: THRESHOLD_WRITE)
        ├─ Création alerte si dépassement (alerts) │
        │                                         │
        ├─ WebSocket Broadcast (/topic/machines/...) ──► [Clients Web / Mobiles]
        ▼
[MongoDB: smartfactory]
```

---

## B. Inventaire Complet des Fichiers Source

### 1. Classe Principale
- **`com/smartfactory/SmartFactoryApplication.java`**
  - **Rôle** : Point d'entrée de l'application Spring Boot.
  - **Responsabilités** : Initialise le contexte Spring (`@SpringBootApplication`) et active l'audit automatique des dates de création et modification des entités MongoDB via `@EnableMongoAuditing`.
  - **Dépendances** : Spring Boot.
  - **Modifications** : Fichier existant conservé.

---

### 2. Package `com.smartfactory.config`
- **`config/DataInitializer.java`**
  - **Rôle** : Initialiseur de données au démarrage (`CommandLineRunner`).
  - **Responsabilités** : Vérifie l'existence du compte administrateur initial (`admin@smartfactory.com` / `adminpassword123`) et de la machine par défaut `CNC-024`. Initialise automatiquement les seuils persistés de surveillance en base MongoDB pour les 4 caractéristiques (`TEMPERATURE`, `VIBRATION`, `CURRENT`, `RPM`).
  - **Dépendances** : `UserRepository`, `MachineRepository`, `PasswordEncoder`, `MachineThresholdService`.
  - **Modifications** : Injecté `MachineThresholdService` pour alimenter automatiquement les seuils persistés par défaut lors du premier démarrage.

- **`config/MqttConfig.java`**
  - **Rôle** : Configuration du client de souscription MQTT.
  - **Responsabilités** : Instancie le `MqttPahoMessageDrivenChannelAdapter`, souscrit au topic wildcard `factory/machines/#`, définit la qualité de service (QoS 1) et achemine les messages entrants vers le service d'ingestion via un `ServiceActivator`.
  - **Dépendances** : `SensorReadingService`, Eclipse Paho.
  - **Modifications** : Fichier existant préservé.

- **`config/SecurityConfig.java`**
  - **Rôle** : Configuration centrale de la sécurité web Spring Security 6.
  - **Responsabilités** : Configure la chaîne de filtres (`SecurityFilterChain`), désactive le CSRF pour les API REST stateless, configure les en-têtes CORS, active la politique de session sans état, branche le filtre `JwtAuthenticationFilter`, et définit les règles d'accès HTTP au niveau des filtres tout en déléguant le contrôle fin des permissions aux annotations `@PreAuthorize`.
  - **Dépendances** : `JwtAuthenticationFilter`, `JwtAuthenticationEntryPoint`, `CustomAccessDeniedHandler`.
  - **Modifications** : Mise à jour des `requestMatchers` pour permettre aux utilisateurs disposant des privilèges granulaires (`USER_READ`, `MACHINE_WRITE`, `THRESHOLD_WRITE`, etc.) de franchir le filtre HTTP sans être bloqués par un rôle dur `ADMIN`.

- **`config/WebSocketConfig.java`**
  - **Rôle** : Configuration du serveur WebSocket et protocole STOMP.
  - **Responsabilités** : Enregistre le point de terminaison `/ws` avec fallback SockJS et active le broker de messages en mémoire pour les préfixes de destination `/topic` et `/app`.
  - **Dépendances** : Spring WebSocket / STOMP.
  - **Modifications** : Fichier existant préservé.

---

### 3. Package `com.smartfactory.security`
- **`security/Permission.java`** *(NOUVEAU)*
  - **Rôle** : Énumération officielle de toutes les permissions granulaires du backend.
  - **Responsabilités** : Définit les privilèges individuels attribuables : `MACHINE_READ`, `MACHINE_WRITE`, `READINGS_READ`, `THRESHOLD_READ`, `THRESHOLD_WRITE`, `ALERT_READ`, `ALERT_ACKNOWLEDGE`, `USER_READ`, `USER_WRITE`, `USER_PERMISSIONS_MANAGE`, `GROUP_READ`, `GROUP_WRITE`, `ZONE_READ`, `ZONE_WRITE`. Associe à chaque permission un code, une description intelligible et une catégorie fonctionnelle.
  - **Méthodes clés** : `fromCode(String code)`, `all()`, getters.

- **`security/Role.java`**
  - **Rôle** : Énumération des rôles métier servant de modèles de privilèges par défaut.
  - **Responsabilités** : Fournit le catalogue des rôles : `ADMIN`, `OPERATOR`, `TECHNICIAN`, `RESPONSABLE_INDUSTRIEL`. Chaque rôle contient un ensemble immuable de permissions par défaut (`getDefaultPermissions()`), servant de socle avant toute personnalisation individuelle.
  - **Méthodes clés** : `getDefaultPermissions()`, `hasDefaultPermission(Permission permission)`, `getLabel()`.
  - **Modifications** : Ajout du mapping des permissions par défaut et des méthodes d'évaluation des privilèges pour chaque rôle.

- **`security/UserPrincipal.java`**
  - **Rôle** : Implémentation Spring Security de `UserDetails`.
  - **Responsabilités** : Représente l'utilisateur authentifié dans le `SecurityContext`. Contient l'identifiant MongoDB, l'email, le hash de mot de passe, le rôle, et la collection des `GrantedAuthority`. Génère à la fois l'autorité de rôle (`ROLE_ADMIN`, `ROLE_OPERATOR`, etc.) et chaque autorité de permission unitaire effective (`MACHINE_READ`, `THRESHOLD_WRITE`, etc.).
  - **Méthodes clés** : `create(User user)`, `hasPermission(Permission p)`, `getEffectivePermissions()`.
  - **Modifications** : Intégration dynamique des permissions effectives de l'utilisateur sous forme de `GrantedAuthority` Spring Security.

- **`security/JwtService.java`**
  - **Rôle** : Service cryptographique de gestion des JSON Web Tokens.
  - **Responsabilités** : Génère les tokens signés HMAC-SHA (HS512), valide leur signature et leur date d'expiration, et extrait le sujet (ID utilisateur MongoDB), l'email et le rôle.
  - **Dépendances** : JJWT API, application configuration (`jwt.secret`, `jwt.expiration-ms`).
  - **Modifications** : Fichier existant préservé.

- **`security/JwtAuthenticationFilter.java`**
  - **Rôle** : Filtre d'interception HTTP (`OncePerRequestFilter`).
  - **Responsabilités** : Récupère le header `Authorization: Bearer <token>`, valide le jeton JWT, charge l'entité `User` depuis la base MongoDB, instancie le `UserPrincipal` contenant tous les privilèges effectifs et place l'objet `UsernamePasswordAuthenticationToken` dans le `SecurityContextHolder`.
  - **Dépendances** : `JwtService`, `UserRepository`.
  - **Modifications** : Fichier existant préservé.

- **`security/JwtAuthenticationEntryPoint.java`**
  - **Rôle** : Gestionnaire d'échec d'authentification (HTTP 401).
  - **Responsabilités** : Retourne une réponse JSON standardisée avec le code `UNAUTHORIZED` lorsqu'une requête anonyme tente d'accéder à une ressource protégée.
  - **Modifications** : Fichier existant préservé.

- **`security/CustomAccessDeniedHandler.java`**
  - **Rôle** : Gestionnaire de refus d'accès (HTTP 403 Forbidden).
  - **Responsabilités** : Formate une réponse JSON standardisée avec le code `FORBIDDEN` et le message explicite `"Access is denied: insufficient permissions"` lorsqu'un utilisateur authentifié ne dispose pas de la permission requise.
  - **Modifications** : Fichier existant préservé.

---

### 4. Package `com.smartfactory.entity`
- **`entity/User.java`**
  - **Rôle** : Entité modèle de l'utilisateur stockée dans la collection MongoDB `users`.
  - **Responsabilités** : Contient l'identité, l'email, le mot de passe hashé, le rôle template, le statut (`ACTIVE`/`INACTIVE`), et les deux ensembles de personnalisation : `customGrantedPermissions` (privilèges accordés) et `customRevokedPermissions` (privilèges révoqués). Calcule les privilèges effectifs via `getEffectivePermissions()`.
  - **Méthodes clés** : `getEffectivePermissions()`, `hasEffectivePermission(Permission p)`, `applyPermissionsChecklist(Collection<Permission> checked)`.
  - **Modifications** : Ajout des champs `customGrantedPermissions`, `customRevokedPermissions`, des méthodes de calcul de priorité, et des annotations `@JsonIgnoreProperties` et `@JsonProperty(access = READ_ONLY)`.

- **`entity/MachineThreshold.java`** *(NOUVEAU)*
  - **Rôle** : Entité modèle des seuils de surveillance persistée dans la collection `machine_thresholds`.
  - **Responsabilités** : Définit les seuils associés à une machine et un type de capteur : `warningMin`, `warningMax`, `criticalMin`, `criticalMax`, unité, état actif/inactif (`active`), description. Évalue à chaud les valeurs de télémétrie mesurées avec priorité aux alertes critiques.
  - **Index** : Index composé unique `{machineId: 1, sensorType: 1}`.
  - **Méthodes clés** : `evaluate(double value)`, `EvaluationResult`.

- **`entity/Machine.java`**
  - **Rôle** : Modèle d'une machine industrielle (`machines`).
  - **Responsabilités** : Code unique (`code`, ex. CNC-024), nom, type, zone associée (`zoneId`), statut (`RUNNING`, `IDLE`, `MAINTENANCE`, `OFFLINE`), liste de caractéristiques techniques.
  - **Modifications** : Fichier existant préservé.

- **`entity/MachineCharacteristic.java`**
  - **Rôle** : Sous-document embarqué pour les caractéristiques d'une machine (nom, valeur, type).
  - **Modifications** : Fichier existant préservé.

- **`entity/MachineStatus.java`**
  - **Rôle** : Énumération des états machine (`IDLE`, `RUNNING`, `MAINTENANCE`, `OFFLINE`).
  - **Modifications** : Fichier existant préservé.

- **`entity/Sensor.java`**
  - **Rôle** : Modèle d'un capteur associé à une machine (`sensors`).
  - **Responsabilités** : Stocke le type de capteur, l'unité physique, le statut en ligne/hors ligne et la date de dernière mesure (`lastSeen`).
  - **Modifications** : Fichier existant préservé.

- **`entity/SensorReading.java`**
  - **Rôle** : Modèle d'une mesure télémétrique horodatée (`sensor_readings`).
  - **Responsabilités** : Machine ID, capteur ID, type, valeur numérique, unité, timestamp de mesure, timestamp de réception.
  - **Modifications** : Fichier existant préservé.

- **`entity/SensorType.java`**
  - **Rôle** : Énumération des types de capteurs industriels (`TEMPERATURE`, `VIBRATION`, `CURRENT`, `RPM`).
  - **Responsabilités** : Fournit l'unité par défaut et valide la conformité des unités transmises (`isValidUnit`).
  - **Modifications** : Fichier existant préservé.

- **`entity/SensorStatus.java`**
  - **Rôle** : Énumération du statut d'un capteur (`ONLINE`, `OFFLINE`).
  - **Modifications** : Fichier existant préservé.

- **`entity/Alert.java`**
  - **Rôle** : Modèle d'une anomalie détectée (`alerts`).
  - **Responsabilités** : Machine ID, sensor ID, sévérité (`WARNING`, `CRITICAL`), valeur mesurée, seuil franchi, statut (`ACTIVE`, `ACKNOWLEDGED`, `RESOLVED`), horodatage.
  - **Modifications** : Fichier existant préservé.

- **`entity/Zone.java`**
  - **Rôle** : Modèle d'une zone géographique dans l'usine (`zones`).
  - **Modifications** : Fichier existant préservé.

- **`entity/Group.java`**
  - **Rôle** : Modèle d'un groupe d'opérateurs supervisé (`groups`).
  - **Modifications** : Fichier existant préservé.

- **`entity/OtpVerification.java` & `entity/OtpType.java`**
  - **Rôle** : Modèle de vérification des codes OTP par email (inscription et réinitialisation de mot de passe).
  - **Modifications** : Fichiers existants préservés.

---

### 5. Package `com.smartfactory.dto`
- **`dto/UserPermissionsDto.java`** *(NOUVEAU)*
  - **Rôle** : DTO de restitution complète des permissions d'un utilisateur.
  - **Champs** : `userId`, `email`, `role`, `roleDefaultPermissions`, `customGrantedPermissions`, `customRevokedPermissions`, `effectivePermissions`.

- **`dto/UpdatePermissionsRequest.java`** *(NOUVEAU)*
  - **Rôle** : DTO de mise à jour des privilèges d'un utilisateur.
  - **Champs** : `permissions` (checklist complète), ou `grantedPermissions` et `revokedPermissions` (ajustement différentiel).

---

### 6. Package `com.smartfactory.repository`
- **`repository/MachineThresholdRepository.java`** *(NOUVEAU)*
  - **Rôle** : Interface Spring Data MongoDB pour la collection `machine_thresholds`.
  - **Méthodes** : `findByMachineIdAndSensorType`, `findByMachineId`, `findByMachineCode`, `existsByMachineIdAndSensorType`, `deleteByMachineIdAndSensorType`, `deleteByMachineId`.

- **`repository/UserRepository.java`**
  - **Rôle** : Accès aux utilisateurs (`users`). Recherche par email, par rôle, recherche textuelle paginée.
  - **Modifications** : Fichier existant préservé.

- **`repository/MachineRepository.java`**
  - **Rôle** : Accès aux machines (`machines`). Recherche par code unique ou identifiant.
  - **Modifications** : Fichier existant préservé.

- **`repository/SensorRepository.java`**
  - **Rôle** : Accès aux capteurs (`sensors`).
  - **Modifications** : Fichier existant préservé.

- **`repository/SensorReadingRepository.java`**
  - **Rôle** : Accès aux lectures de capteurs (`sensor_readings`). Requêtes temporelles et déduplication.
  - **Modifications** : Fichier existant préservé.

- **`repository/AlertRepository.java`**
  - **Rôle** : Accès aux alertes de surveillance (`alerts`).
  - **Modifications** : Fichier existant préservé.

- **`repository/ZoneRepository.java`**
  - **Rôle** : Accès aux zones d'usine (`zones`).
  - **Modifications** : Fichier existant préservé.

- **`repository/GroupRepository.java`**
  - **Rôle** : Accès aux groupes d'opérateurs (`groups`).
  - **Modifications** : Fichier existant préservé.

- **`repository/OtpRepository.java`**
  - **Rôle** : Accès aux codes OTP (`otp_verifications`).
  - **Modifications** : Fichier existant préservé.

---

### 7. Package `com.smartfactory.service`
- **`service/MachineThresholdService.java`** *(NOUVEAU)*
  - **Rôle** : Service cœur de gestion et d'évaluation des seuils configurables.
  - **Responsabilités** :
    - Cache mémoire thread-safe via `ConcurrentHashMap<String, Map<SensorType, MachineThreshold>>`.
    - Chargement O(1) sur chaque trame MQTT sans requêter MongoDB.
    - Invalidation immédiate du cache lors des créations, modifications ou suppressions via l'API REST.
    - Validation stricte de cohérence (`criticalMin <= warningMin <= warningMax <= criticalMax`).
    - Fallback transparent vers les seuils de référence industriels si aucun seuil n'est encore configuré.
    - Initialisation automatique en base des seuils par défaut pour les machines.
  - **Méthodes clés** : `evaluateReading(Machine, SensorType, double)`, `getThresholdsByMachine(String)`, `saveOrUpdateThreshold(String, MachineThreshold)`, `deleteThreshold(String, SensorType)`.

- **`service/SensorReadingService.java`**
  - **Rôle** : Pipeline de traitement et validation des messages de télémétrie MQTT.
  - **Responsabilités** : Parse le payload JSON, valide le code machine et le type de capteur, vérifie les valeurs numériques (rejet NaN/Infinity) et les unités, déduplique les mesures identiques, persiste dans `sensor_readings`, bascule le capteur à `ONLINE`, et invoque l'évaluation des seuils dynamiques.
  - **Modifications** : Remplacement de la méthode avec seuils codés en dur par un appel direct à `machineThresholdService.evaluateReading(machine, type, value)`.

- **`service/UserService.java`**
  - **Rôle** : Logique métier de gestion des utilisateurs et de leurs permissions.
  - **Responsabilités** : CRUD utilisateurs, encodage des mots de passe, préservation des privilèges personnalisés lors des modifications, génération du catalogue de permissions (`getPermissionsCatalog`), et mise à jour de la checklist des permissions individuelles.
  - **Modifications** : Ajout des méthodes `getUserPermissions`, `updateUserPermissions`, `getPermissionsCatalog` et prise en charge des privilèges dans `updateUser`.

- **`service/AuthService.java`**
  - **Rôle** : Authentification, émission des JWT, gestion des OTP et inscription.
  - **Modifications** : Fichier existant préservé.

- **`service/MachineService.java`**
  - **Rôle** : Logique métier de gestion des machines industrielles (recherche, création, modification, suppression).
  - **Modifications** : Fichier existant préservé.

- **`service/SensorService.java`**
  - **Rôle** : Auto-provisioning des capteurs associés aux machines.
  - **Modifications** : Fichier existant préservé.

- **`service/SensorMonitoringService.java`**
  - **Rôle** : Tâche planifiée (`@Scheduled`) de détection des capteurs hors ligne (`OFFLINE`).
  - **Modifications** : Fichier existant préservé.

- **`service/RealtimeMessageService.java`**
  - **Rôle** : Diffusion des lectures, alertes et statuts capteurs via STOMP WebSocket.
  - **Modifications** : Fichier existant préservé.

- **`service/ZoneService.java` & `service/GroupService.java` & `service/EmailService.java` & `service/OtpService.java`**
  - **Rôle** : Services métiers pour les zones, les équipes d'opérateurs, les emails et la gestion des tokens OTP.
  - **Modifications** : Fichiers existants préservés.

---

### 8. Package `com.smartfactory.controller`
- **`controller/MachineThresholdController.java`** *(NOUVEAU)*
  - **Rôle** : Contrôleur REST pour la gestion des seuils des machines.
  - **Endpoints exposés** :
    - `GET /api/machines/{machineId}/thresholds` (`@PreAuthorize("hasAuthority('THRESHOLD_READ')")`)
    - `GET /api/machines/{machineId}/thresholds/{sensorType}` (`@PreAuthorize("hasAuthority('THRESHOLD_READ')")`)
    - `POST /api/machines/{machineId}/thresholds` (`@PreAuthorize("hasAuthority('THRESHOLD_WRITE')")`)
    - `PUT /api/machines/{machineId}/thresholds/{sensorType}` (`@PreAuthorize("hasAuthority('THRESHOLD_WRITE')")`)
    - `DELETE /api/machines/{machineId}/thresholds/{sensorType}` (`@PreAuthorize("hasAuthority('THRESHOLD_WRITE')")`)

- **`controller/UserController.java`**
  - **Rôle** : Gestion des comptes et des permissions utilisateurs.
  - **Endpoints protégés** :
    - `GET /api/users/permissions/catalog` : Public aux authentifiés.
    - `GET /api/users` & `GET /api/users/{id}` : `@PreAuthorize("hasAuthority('USER_READ')")`.
    - `POST /api/users`, `PUT /api/users/{id}`, `DELETE /api/users/{id}` : `@PreAuthorize("hasAuthority('USER_WRITE')")`.
    - `GET /api/users/{id}/permissions` : `@PreAuthorize("hasAuthority('USER_READ')")`.
    - `PUT /api/users/{id}/permissions` : `@PreAuthorize("hasAuthority('USER_PERMISSIONS_MANAGE')")`.
  - **Modifications** : Remplacement des rôles codés en dur par les vérifications de permissions effectives et ajout des routes de permissions.

- **`controller/MachineController.java`**
  - **Rôle** : Gestion des machines industrielles.
  - **Endpoints protégés** :
    - `GET /api/machines`, `GET /api/machines/{id}`, `GET /api/machines/{id}/sensors` : `@PreAuthorize("hasAuthority('MACHINE_READ')")`.
    - `POST`, `PUT`, `DELETE /api/machines/**` : `@PreAuthorize("hasAuthority('MACHINE_WRITE')")`.
  - **Modifications** : Migration de `@PreAuthorize("hasRole('ADMIN')")` vers les privilèges `MACHINE_READ` et `MACHINE_WRITE`.

- **`controller/ZoneController.java`**
  - **Rôle** : Gestion des zones d'atelier.
  - **Endpoints protégés** : `ZONE_READ` pour la consultation, `ZONE_WRITE` pour la création/modification/suppression.
  - **Modifications** : Migration vers les permissions granulaires.

- **`controller/GroupController.java`**
  - **Rôle** : Gestion des équipes d'opérateurs et superviseurs.
  - **Endpoints protégés** : `GROUP_READ` pour la consultation, `GROUP_WRITE` pour l'administration.
  - **Modifications** : Migration vers les permissions granulaires.

- **`controller/SensorReadingController.java`**
  - **Rôle** : Consultation de l'historique télémétrique des capteurs.
  - **Endpoints protégés** : `GET /api/machines/{id}/readings` protégé par `@PreAuthorize("hasAuthority('READINGS_READ')")`.
  - **Modifications** : Ajout du contrôle de privilège `READINGS_READ`.

- **`controller/AuthController.java`**
  - **Rôle** : Authentification, inscription, réinitialisation de mot de passe et endpoint `/api/auth/me`.
  - **Modifications** : Fichier existant préservé.

- **`controller/GlobalExceptionHandler.java`**
  - **Rôle** : Intercepteur d'exceptions global (`@RestControllerAdvice`).
  - **Responsabilités** : Normalise les réponses d'erreur JSON (`ResponseStatusException`, validation d'arguments, `AccessDeniedException` en 403 Forbidden, `HttpMessageNotReadableException` en 400 Bad Request).
  - **Modifications** : Ajout du gestionnaire pour `HttpMessageNotReadableException`.

---

## C. Fonctionnement du Système de Permissions et Privilèges

### 1. La Checklist des Privilèges
La plateforme dispose de 14 permissions unitaires réparties en 7 domaines d'activité :

| Catégorie | Code de la Permission | Description Métier |
| :--- | :--- | :--- |
| **MACHINES** | `MACHINE_READ` | Consulter les machines, détails et capteurs associés |
| **MACHINES** | `MACHINE_WRITE` | Créer, modifier les paramètres et supprimer des machines |
| **READINGS** | `READINGS_READ` | Consulter l'historique et les flux de télémétrie |
| **THRESHOLDS** | `THRESHOLD_READ` | Consulter les seuils de surveillance des machines |
| **THRESHOLDS** | `THRESHOLD_WRITE` | Configurer, modifier et supprimer les seuils d'alerte |
| **ALERTS** | `ALERT_READ` | Consulter les alertes déclenchées |
| **ALERTS** | `ALERT_ACKNOWLEDGE` | Acquitter et résoudre les alertes actives |
| **USERS** | `USER_READ` | Consulter les comptes utilisateurs et leurs fiches |
| **USERS** | `USER_WRITE` | Créer, modifier les utilisateurs et réinitialiser les mots de passe |
| **USERS** | `USER_PERMISSIONS_MANAGE` | Attribuer et personnaliser les privilèges des utilisateurs |
| **GROUPS** | `GROUP_READ` | Consulter les groupes d'opérateurs et affectations |
| **GROUPS** | `GROUP_WRITE` | Créer, modifier les groupes et assigner superviseurs/opérateurs |
| **ZONES** | `ZONE_READ` | Consulter les zones industrielles de l'usine |
| **ZONES** | `ZONE_WRITE` | Créer, modifier et supprimer des zones |

---

### 2. Le Rôle comme Modèle par Défaut (Template)
Chaque utilisateur possède un rôle d'affectation principal qui fournit un ensemble de permissions par défaut :

- **`ADMIN`** : Toutes les permissions sans restriction (`EnumSet.allOf(Permission.class)`).
- **`RESPONSABLE_INDUSTRIEL`** : `MACHINE_READ`, `MACHINE_WRITE`, `READINGS_READ`, `THRESHOLD_READ`, `THRESHOLD_WRITE`, `ALERT_READ`, `ALERT_ACKNOWLEDGE`, `ZONE_READ`, `GROUP_READ`.
- **`TECHNICIAN`** : `MACHINE_READ`, `READINGS_READ`, `ALERT_READ`, `ALERT_ACKNOWLEDGE`, `THRESHOLD_READ`, `ZONE_READ`, `GROUP_READ`.
- **`OPERATOR`** : `MACHINE_READ`, `READINGS_READ`, `ALERT_READ`, `ZONE_READ`, `GROUP_READ`.

---

### 3. Personnalisation Individuelle et Règle de Priorité Explicite
Deux utilisateurs ayant le même rôle peuvent avoir des privilèges distincts. Par exemple, deux opérateurs :
- **Opérateur A** : Privilèges par défaut de son rôle (`MACHINE_READ`, `READINGS_READ`, `ALERT_READ`, `ZONE_READ`, `GROUP_READ`).
- **Opérateur B** : Même rôle `OPERATOR`, mais avec `THRESHOLD_WRITE` et `MACHINE_WRITE` accordés spécifiquement.

#### Règle de priorité mathématique et déterministe :
Dans l'entité `User`, deux ensembles persistés définissent les ajustements individuels :
1. `customGrantedPermissions` : Permissions explicitement accordées à l'utilisateur (autorisations spécifiques).
2. `customRevokedPermissions` : Permissions explicitement révoquées pour cet utilisateur (interdictions spécifiques).

Le calcul des permissions effectives (`effectivePermissions`) obéit à la formule :
$$\text{Permissions Effectives} = \Big(\text{Permissions du Rôle} \cup \text{customGrantedPermissions}\Big) \setminus \text{customRevokedPermissions}$$

**Règle de résolution :** Le refus explicite (`customRevokedPermissions`) est **strictement prioritaire**. Si une permission figure dans la liste de révocation, elle est immédiatement retirée, même si le rôle par défaut la prévoyait. Si une permission figure dans les autorisations accordées, elle est ajoutée même si le rôle ne la contenait pas.

Lorsqu'un administrateur soumet la checklist complète souhaitée pour un utilisateur via l'API `PUT /api/users/{id}/permissions` :
- Les permissions cochées qui ne sont pas dans le rôle deviennent des `customGrantedPermissions`.
- Les permissions décochées qui faisaient partie du rôle deviennent des `customRevokedPermissions`.
- Les permissions alignées sur le rôle sont nettoyées, conservant une base de données compacte et sans redondance.

---

### 4. Protection Serveur et Rétrocompatibilité
1. **Double verrouillage de sécurité** :
   - Au niveau des filtres de sécurité HTTP (`SecurityConfig`), les requêtes exigent l'authentification et vérifient les autorités.
   - Au niveau des méthodes de contrôleurs, les annotations `@PreAuthorize("hasAuthority('PERMISSION_NAME')")` appliquent le contrôle strict.
2. **Réponse d'erreur** : Un utilisateur authentifié dépourvu de la permission requise reçoit immédiatement un statut HTTP `403 FORBIDDEN` avec le corps JSON :
   ```json
   {
     "status": 403,
     "code": "FORBIDDEN",
     "message": "Access is denied: insufficient permissions",
     "timestamp": "2026-10-11T19:22:22.662940Z"
   }
   ```
3. **Rétrocompatibilité** : L'objet `UserPrincipal` injecte à la fois les autorités de permissions (`MACHINE_READ`, etc.) et l'autorité de rôle avec préfixe (`ROLE_ADMIN`, `ROLE_OPERATOR`, etc.), garantissant que les vérifications historiques de rôles continuent de fonctionner sans rupture.

---

## D. Fonctionnement du Stockage et de l'Évaluation des Seuils

### 1. Modèle de Stockage en Base de Données
Les seuils ne sont plus codés en dur dans le code source Java. Ils sont modélisés par l'entité `MachineThreshold` persistée dans la collection MongoDB `machine_thresholds`.

Chaque document contient :
- `machineId` (String) : Identifiant de la machine associée.
- `machineCode` (String) : Code métier de la machine (ex. `CNC-024`).
- `sensorType` (SensorType) : Type de mesure surveillé (`TEMPERATURE`, `VIBRATION`, `CURRENT`, `RPM`).
- `warningMin` (Double, optionnel) : Seuil inférieur d'avertissement.
- `warningMax` (Double, optionnel) : Seuil supérieur d'avertissement.
- `criticalMin` (Double, optionnel) : Seuil inférieur critique.
- `criticalMax` (Double, optionnel) : Seuil supérieur critique.
- `unit` (String) : Unité de mesure (`°C`, `mm/s`, `A`, `RPM`).
- `active` (boolean) : Indicateur d'activation de la surveillance (permet de suspendre la surveillance sans supprimer la configuration).
- `description` (String) : Description ou justification métier.

Un index composé unique `{machineId: 1, sensorType: 1}` garantit l'intégrité relationnelle et l'unicité de la configuration par grandeur physique et par machine.

---

### 2. Évaluation des Dépassements et Détection d'Anomalies
Lors de l'ingestion d'une mesure télémétrique, la méthode `evaluate(double value)` évalue la valeur selon l'algorithme suivant :
1. Si `active == false`, aucun dépassement n'est signalé (`EvaluationResult.safe()`).
2. **Vérification des seuils critiques (Priorité haute)** :
   - Si `criticalMax != null` et `valeur >= criticalMax` $\rightarrow$ Alerte `CRITICAL`.
   - Si `criticalMin != null` et `valeur <= criticalMin` $\rightarrow$ Alerte `CRITICAL`.
3. **Vérification des seuils d'avertissement** :
   - Si `warningMax != null` et `valeur >= warningMax` $\rightarrow$ Alerte `WARNING`.
   - Si `warningMin != null` et `valeur <= warningMin` $\rightarrow$ Alerte `WARNING`.
4. Si aucun seuil n'est franchi $\rightarrow$ Statut sain.

Lorsqu'une alerte est déclenchée, une entité `Alert` est persistée dans MongoDB et diffusée en temps réel via le WebSocket STOMP sur `/topic/machines/{machineCode}/alerts`.

---

### 3. Stratégie de Performance : Cache en Mémoire et Rechargement à Chaud
Pour supporter un flux continu de mesures IoT (ex. plusieurs mesures par seconde envoyées par le simulateur) sans surcharger la base de données :
- `MachineThresholdService` maintient un cache en mémoire concurrent :
  ```java
  ConcurrentHashMap<String, Map<SensorType, MachineThreshold>> cache
  ```
- **Lecture à chaud (O(1))** : Chaque mesure MQTT consulte le cache en mémoire sans aucune requête MongoDB.
- **Invalidation immédiate** : Dès qu'un utilisateur autorisé appelle l'API REST pour modifier, créer ou supprimer un seuil (`POST`, `PUT`, `DELETE /api/machines/{id}/thresholds/**`), la base de données est mise à jour et l'entrée correspondante dans le cache est invalidée et rafraîchie en temps réel.
- **Prise en compte instantanée** : La modification du seuil est effective dès la mesure télémétrique suivante, sans redémarrer le serveur ni recompiler le code.

---

### 4. Procédure pour Ajouter une Nouvelle Caractéristique Surveillée
1. Ajouter la valeur dans l'énumération `SensorType` (ex. `PRESSURE("bar")`).
2. Définir son unité et les formats acceptés dans `SensorType.isValidUnit()`.
3. Le simulateur ou le capteur peut alors émettre sur `factory/machines/{code}/PRESSURE`.
4. Configurer ses seuils via l'endpoint REST `POST /api/machines/{machineId}/thresholds`. Le système intègre la nouvelle mesure sans modification de la structure de base.

---

## E. Référentiel Complet des Endpoints API REST

### 1. Authentification & Profil (`/api/auth`)
| Méthode | URL | Description | Paramètres / Corps | Permission Requise | Réponses |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `POST` | `/api/auth/login` | Connexion et obtention du JWT | `{"email", "password"}` | Public | `200 OK`, `401 Unauthorized` |
| `POST` | `/api/auth/logout` | Déconnexion | Aucun | Public | `200 OK` |
| `POST` | `/api/auth/register` | Création de compte avec envoi OTP | Corps `User` | Public | `201 Created`, `409 Conflict` |
| `POST` | `/api/auth/verify-email` | Validation du compte par code OTP | `{"email", "otp"}` | Public | `200 OK`, `400 Bad Request` |
| `POST` | `/api/auth/resend-verification` | Renvoi d'un code OTP d'inscription | `{"email"}` | Public | `200 OK`, `400 Bad Request` |
| `POST` | `/api/auth/forgot-password` | Demande de réinitialisation mot de passe | `{"email"}` | Public | `200 OK` |
| `POST` | `/api/auth/verify-reset-otp` | Validation du code OTP de réinitialisation | `{"email", "otp"}` | Public | `200 OK`, `400 Bad Request` |
| `POST` | `/api/auth/reset-password` | Réinitialisation du mot de passe | `{"email", "resetToken", "newPassword"}` | Public | `200 OK`, `400 Bad Request` |
| `GET` | `/api/auth/me` | Informations de l'utilisateur connecté | Header `Authorization` | Authentifié | `200 OK`, `401 Unauthorized` |

---

### 2. Gestion des Utilisateurs & Permissions (`/api/users`)
| Méthode | URL | Description | Paramètres / Corps | Permission Requise | Réponses |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `GET` | `/api/users/permissions/catalog` | Catalogue global des permissions et rôles | Aucun | Authentifié | `200 OK` |
| `GET` | `/api/users` | Liste paginée des utilisateurs | `search`, `role`, `page`, `size`, `sort` | `USER_READ` | `200 OK`, `403 Forbidden` |
| `GET` | `/api/users/{id}` | Détail d'un utilisateur | ID utilisateur en URL | `USER_READ` | `200 OK`, `404 Not Found` |
| `POST` | `/api/users` | Création d'un nouvel utilisateur | Corps `User` complet | `USER_WRITE` | `201 Created`, `400 Bad Request`, `409 Conflict` |
| `PUT` | `/api/users/{id}` | Mise à jour des informations utilisateur | ID en URL, corps `User` | `USER_WRITE` | `200 OK`, `400 Bad Request` |
| `DELETE` | `/api/users/{id}` | Suppression d'un compte utilisateur | ID en URL | `USER_WRITE` | `204 No Content`, `404 Not Found` |
| `PUT` | `/api/users/change-password/{id}` | Modification de mot de passe par un admin | ID en URL, `newPassword`, `oldPassword` | `USER_WRITE` | `204 No Content`, `400 Bad Request` |
| `GET` | `/api/users/{id}/permissions` | Consultation détaillée des privilèges | ID utilisateur en URL | `USER_READ` | `200 OK` (`UserPermissionsDto`) |
| `PUT` | `/api/users/{id}/permissions` | Attribution / mise à jour de la checklist | Corps `UpdatePermissionsRequest` | `USER_PERMISSIONS_MANAGE` | `200 OK` (`UserPermissionsDto`), `403 Forbidden` |
| `PUT` | `/api/users/me` | Mise à jour de son propre profil | Corps `User` (nom, prénom, email) | Authentifié | `200 OK` |
| `PUT` | `/api/users/me/password` | Modification de son propre mot de passe | `newPassword`, `oldPassword` | Authentifié | `204 No Content`, `400 Bad Request` |

---

### 3. Gestion des Machines Industrielles (`/api/machines`)
| Méthode | URL | Description | Paramètres / Corps | Permission Requise | Réponses |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `GET` | `/api/machines` | Liste des machines avec filtres et pagination | `zoneId`, `status`, `search`, `page`, `size` | `MACHINE_READ` | `200 OK`, `403 Forbidden` |
| `GET` | `/api/machines/{id}` | Détail complet d'une machine | ID machine en URL | `MACHINE_READ` | `200 OK`, `404 Not Found` |
| `POST` | `/api/machines` | Enregistrement d'une nouvelle machine | Corps `Machine` | `MACHINE_WRITE` | `201 Created`, `409 Conflict` |
| `PUT` | `/api/machines/{id}` | Mise à jour des paramètres d'une machine | ID en URL, corps `Machine` | `MACHINE_WRITE` | `200 OK`, `400 Bad Request` |
| `DELETE` | `/api/machines/{id}` | Suppression d'une machine | ID en URL | `MACHINE_WRITE` | `204 No Content`, `404 Not Found` |
| `GET` | `/api/machines/{id}/sensors` | Liste des capteurs d'une machine | ID en URL | `MACHINE_READ` | `200 OK` |
| `GET` | `/api/machines/{id}/readings` | Historique des mesures télémétriques | `from`, `to`, `sensorType`, `page`, `size` | `READINGS_READ` | `200 OK`, `400 Bad Request` |

---

### 4. Gestion des Seuils de Surveillance (`/api/machines/{machineId}/thresholds`)
| Méthode | URL | Description | Paramètres / Corps | Permission Requise | Réponses |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `GET` | `/api/machines/{machineId}/thresholds` | Liste de tous les seuils de la machine | ID machine en URL | `THRESHOLD_READ` | `200 OK`, `404 Not Found` |
| `GET` | `/api/machines/{machineId}/thresholds/{sensorType}` | Seuil d'un capteur spécifique | ID machine, nom capteur | `THRESHOLD_READ` | `200 OK`, `404 Not Found` |
| `POST` | `/api/machines/{machineId}/thresholds` | Création / remplacement d'un seuil | Corps `MachineThreshold` | `THRESHOLD_WRITE` | `201 Created`, `400 Bad Request` |
| `PUT` | `/api/machines/{machineId}/thresholds/{sensorType}` | Mise à jour d'un seuil spécifique | ID, type, corps `MachineThreshold` | `THRESHOLD_WRITE` | `200 OK`, `400 Bad Request` |
| `DELETE` | `/api/machines/{machineId}/thresholds/{sensorType}` | Suppression d'un seuil de surveillance | ID, type capteur | `THRESHOLD_WRITE` | `204 No Content` |

---

### 5. Zones & Groupes d'Opérateurs (`/api/zones` & `/api/groups`)
| Méthode | URL | Description | Paramètres / Corps | Permission Requise | Réponses |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `GET` | `/api/zones` | Liste de toutes les zones industrielles | Aucun | `ZONE_READ` | `200 OK` |
| `GET` | `/api/zones/{id}` | Détail d'une zone | ID en URL | `ZONE_READ` | `200 OK`, `404 Not Found` |
| `POST` | `/api/zones` | Création d'une zone | Corps `Zone` | `ZONE_WRITE` | `201 Created`, `409 Conflict` |
| `PUT` | `/api/zones/{id}` | Mise à jour d'une zone | ID en URL, corps `Zone` | `ZONE_WRITE` | `200 OK` |
| `DELETE` | `/api/zones/{id}` | Suppression d'une zone | ID en URL | `ZONE_WRITE` | `204 No Content` |
| `GET` | `/api/zones/{id}/machines` | Liste des machines situées dans une zone | ID en URL | `ZONE_READ` | `200 OK` |
| `GET` | `/api/groups` | Liste paginée des groupes d'opérateurs | `search`, `page`, `size` | `GROUP_READ` | `200 OK` |
| `GET` | `/api/groups/{id}` | Détail d'un groupe | ID en URL | `GROUP_READ` | `200 OK`, `404 Not Found` |
| `POST` | `/api/groups` | Création d'un groupe | Corps `Group` | `GROUP_WRITE` | `201 Created`, `409 Conflict` |
| `PUT` | `/api/groups/{id}` | Modification d'un groupe | ID en URL, corps `Group` | `GROUP_WRITE` | `200 OK` |
| `DELETE` | `/api/groups/{id}` | Suppression d'un groupe | ID en URL | `GROUP_WRITE` | `204 No Content` |
| `POST` | `/api/groups/{id}/operators/{userId}` | Ajout d'un opérateur dans un groupe | ID groupe, ID utilisateur | `GROUP_WRITE` | `200 OK`, `409 Conflict` |
| `DELETE` | `/api/groups/{id}/operators/{userId}` | Retrait d'un opérateur d'un groupe | ID groupe, ID utilisateur | `GROUP_WRITE` | `200 OK` |
| `PUT` | `/api/groups/{id}/supervisor` | Affectation du superviseur d'un groupe | ID groupe, `{"supervisorId"}` | `GROUP_WRITE` | `200 OK` |
| `GET` | `/api/groups/{id}/operators` | Liste des opérateurs du groupe | ID en URL | `GROUP_READ` | `200 OK` |
| `GET` | `/api/groups/{id}/supervisor` | Superviseur du groupe | ID en URL | `GROUP_READ` | `200 OK`, `204 No Content` |

---

### 6. Dashboard & Supervision Industrielle (`/api/dashboard`) — Sprint 7 (US51 à US55)
Accessible par : `ADMIN`, `RESPONSABLE_INDUSTRIEL`, `TECHNICIAN`. Accès strictement refusé aux opérateurs (`OPERATOR` $\rightarrow$ `403 Forbidden`).

| Méthode | URL | Description | Paramètres / Corps | Permission Requise | Réponses |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `GET` | `/api/dashboard/overview` | **US51** : Vue d'ensemble du Dashboard industriel (synthèse globale temps réel de l'usine) | Aucun | `ADMIN`, `RESPONSABLE_INDUSTRIEL`, `TECHNICIAN` | `200 OK`, `403 Forbidden` |
| `GET` | `/api/dashboard/kpis` | **US52** : Indicateurs industriels clés (Disponibilité, MTBF, MTTR, TRS/OEE) | `period` (`day`, `week`, `month`), `machineId` (optionnel) | `ADMIN`, `RESPONSABLE_INDUSTRIEL`, `TECHNICIAN` | `200 OK`, `404 Not Found` |
| `GET` | `/api/dashboard/kpis/history` | **US52** : Série temporelle historique des indicateurs KPIs | `period` (`week`, `month`), `machineId`, `points` | `ADMIN`, `RESPONSABLE_INDUSTRIEL`, `TECHNICIAN` | `200 OK` |
| `GET` | `/api/dashboard/factory-state` | **US53** : État synthétique de l'usine et agrégation par zones industrielles | Aucun | `ADMIN`, `RESPONSABLE_INDUSTRIEL`, `TECHNICIAN` | `200 OK` |
| `GET` | `/api/dashboard/zones/{zoneId}` | **US53** : Supervision détaillée d'une zone (machines rattachées, santé, alertes) | ID zone en URL | `ADMIN`, `RESPONSABLE_INDUSTRIEL`, `TECHNICIAN` | `200 OK`, `404 Not Found` |
| `GET` | `/api/dashboard/anomalies` | **US54** : Supervision globale des anomalies (recherche, filtres, pagination, tri) | `period`, `severity`, `status`, `machineId`, `zoneId`, `page`, `size`, `sort` | `ADMIN`, `RESPONSABLE_INDUSTRIEL`, `TECHNICIAN` | `200 OK` |
| `GET` | `/api/dashboard/anomalies/stats` | **US54** : Statistiques globales des anomalies (par sévérité, statut, capteur, top machines) | `period` (`day`, `week`, `month`) | `ADMIN`, `RESPONSABLE_INDUSTRIEL`, `TECHNICIAN` | `200 OK` |
| `GET` | `/api/dashboard/health-scores` | **US55** : Score de santé (0-100) pour l'ensemble des machines du parc industriel | Aucun | `ADMIN`, `RESPONSABLE_INDUSTRIEL`, `TECHNICIAN` | `200 OK` |
| `GET` | `/api/dashboard/health-scores/{machineId}` | **US55** : Décomposition détaillée du score de santé machine et recommandations | ID machine en URL | `ADMIN`, `RESPONSABLE_INDUSTRIEL`, `TECHNICIAN` | `200 OK`, `404 Not Found` |

#### Formules et Règles de Calcul des KPIs (US52 & US55) :
1. **Taux de Disponibilité (Availability %)** :
   $$\text{Disponibilité} = \left(\frac{\text{Temps Opérationnel Observé}}{\text{Temps Total Période}}\right) \times 100$$
   Calculé à partir du temps passé hors pannes et de la somme des durées des interventions correctives sur l'intervalle.
2. **MTBF (Mean Time Between Failures)** :
   $$\text{MTBF (heures)} = \frac{\text{Total Heures de Fonctionnement}}{\text{Nombre de Pannes ou Interventions Correctives}}$$
   Si aucune panne n'est survenue sur l'intervalle, la valeur correspond au temps de fonctionnement complet avec mention explicite *"Aucune panne constatée sur la période"*.
3. **MTTR (Mean Time To Repair)** :
   $$\text{MTTR (minutes/heures)} = \frac{\sum \text{Durée des réparations des interventions clôturées}}{\text{Nombre d'interventions correctives clôturées}}$$
4. **Justification de l'Indisponibilité du TRS / OEE** :
   Dans le simulateur IoT actuel, les capteurs ne remontent que `TEMPERATURE`, `VIBRATION`, `CURRENT`, et `RPM`. Les grandeurs nécessaires au calcul du TRS (temps de cycle nominal, comptage des pièces bonnes vs rebuts pour le taux de qualité) ne sont pas instrumentées. En stricte conformité métier, l'API renvoie un statut `"UNAVAILABLE"`, `value: null`, accompagné de la liste des champs manquants (`missingMetrics`) au lieu d'une valeur factice.
5. **Algorithme Déterministe du Health Score (0 - 100)** :
   - Score initial : `100`.
   - Déduction statut machine : `RUNNING` (0), `IDLE` (-5), `OFFLINE` (-15), `MAINTENANCE` (-20), `FAILURE` (-40).
   - Déduction alertes actives : Alerte critique active (-25/alerte, max -50), Alerte warning (-10/alerte, max -30).
   - Déduction anomalies ouvertes : -10 pour anomalie critique, -5 pour anomalie warning (max -25).
   - Déduction télémétrie capteurs : Dépassement critique seuil (-15), dépassement warning (-5).
   - Évaluation : $\ge 80$ : `EXCELLENT`, $60-79$ : `GOOD`, $40-59$ : `WARNING`, $<40$ : `CRITICAL`.
   - Machines sans capteurs/télémétrie hors ligne : Statut `"INSUFFICIENT_DATA"` avec recommandation d'instrumentation.

---

### 7. Paramètres & Préférences de Notification (`/api/settings` & `/api/notifications/preferences`)
| Méthode | URL | Description | Paramètres / Corps | Permission Requise | Réponses |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `GET` | `/api/settings` | Paramètres utilisateur et préférence push | Aucun | Authentifié | `200 OK` |
| `PATCH`, `PUT` | `/api/settings` | Mise à jour des paramètres et activation/désactivation push | `{"notificationsEnabled": boolean}` | Authentifié | `200 OK` |
| `GET` | `/api/notifications/preferences` | Préférences de notification de l'utilisateur connecté | Aucun | Authentifié | `200 OK` |
| `PATCH`, `PUT` | `/api/notifications/preferences` | Bascule du toggle push (on/off) | `{"notificationsEnabled": boolean}` | Authentifié | `200 OK` |
| `GET` | `/api/users/{id}/preferences` | Consultation des préférences d'un utilisateur par ID | ID utilisateur en URL | Authentifié | `200 OK` |
| `PATCH`, `PUT` | `/api/users/{id}/preferences` | Mise à jour des préférences d'un utilisateur par ID | ID en URL, `{"notificationsEnabled": boolean}` | Authentifié | `200 OK` |

---

### 8. Maintenance & Interventions Industrielles (`/api/maintenance`) — Sprint 6
| Méthode | URL | Description | Paramètres / Corps | Permission Requise | Réponses |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `GET` | `/api/maintenance` | Liste des interventions (filtrée par technicien si TECHNICIEN) | `status`, `type`, `page`, `size` | `MAINTENANCE_READ` | `200 OK`, `403 Forbidden` |
| `GET` | `/api/maintenance/{id}` | Détail complet d'une intervention | ID en URL | `MAINTENANCE_READ` | `200 OK`, `403 Forbidden`, `404 Not Found` |
| `POST` | `/api/maintenance` | Création d'une intervention | Corps `MaintenanceIntervention` | `RESPONSABLE_INDUSTRIEL`, `ADMIN` | `201 Created`, `403 Forbidden` |
| `POST`, `PUT` | `/api/maintenance/{id}/start` | **Démarrage d'une intervention** (passe à `IN_PROGRESS`, machine à `MAINTENANCE`, notifie le Responsable Industriel) | ID intervention en URL | `TECHNICIAN`, `RESPONSABLE_INDUSTRIEL`, `ADMIN` | `200 OK`, `404 Not Found` |
| `PUT` | `/api/maintenance/{id}/assign` | Assignation ou réassignation de techniciens | `{"technicianId": "..."}` ou `{"technicianIds": [...]}` | `RESPONSABLE_INDUSTRIEL`, `ADMIN` | `200 OK`, `403 Forbidden` |
| `POST` | `/api/maintenance/{id}/diagnoses` | Enregistrement d'un diagnostic technique | Corps `MaintenanceDiagnosis` | Technicien assigné, `ADMIN` | `200 OK`, `403 Forbidden` |
| `POST` | `/api/maintenance/{id}/actions` | Ajout d'une action de réparation | Corps `MaintenanceAction` | Technicien assigné, `ADMIN` | `200 OK`, `403 Forbidden` |
| `POST` | `/api/maintenance/{id}/observations` | Ajout d'une observation technique | Corps `MaintenanceObservation` | Technicien assigné, `ADMIN` | `200 OK`, `403 Forbidden` |
| `PUT` | `/api/maintenance/{id}/close` | Clôture de l'intervention (statut `COMPLETED`, restauration statut machine) | `{"closureNotes", "restoreMachineStatus"}` | Technicien assigné, `ADMIN` | `200 OK`, `403 Forbidden` |

---

## F. Schéma de la Base de Données et Migration

### 1. Collections MongoDB et Relations
Le système utilise **Spring Data MongoDB**. Les collections principales sont :

1. **`users`** :
   - `_id` (String) : Identifiant unique.
   - `email` (String) : Unique (`@Indexed(unique = true)`).
   - `role` (String) : Rôle par défaut (`ADMIN`, `OPERATOR`, `TECHNICIAN`, `RESPONSABLE_INDUSTRIEL`).
   - `customGrantedPermissions` (Array de String) : Privilèges accordés individuellement.
   - `customRevokedPermissions` (Array de String) : Privilèges révoqués individuellement.
   - `status` (String) : `ACTIVE` ou `INACTIVE`.
   - `emailVerified` (Boolean).

2. **`machine_thresholds`** *(NOUVELLE COLLECTION)* :
   - `_id` (String) : Identifiant unique.
   - `machineId` (String) : Clé étrangère logique vers `machines._id`.
   - `machineCode` (String) : Code machine (ex. `CNC-024`).
   - `sensorType` (String) : Type de mesure (`TEMPERATURE`, `VIBRATION`, `CURRENT`, `RPM`).
   - `warningMin`, `warningMax` (Double).
   - `criticalMin`, `criticalMax` (Double).
   - `unit` (String) : Unité physique.
   - `active` (Boolean) : Statut de surveillance.
   - Index composé unique : `{ machineId: 1, sensorType: 1 }`.

3. **`machines`** :
   - `_id` (String), `code` (String, unique), `name`, `type`, `zoneId`, `status`, `caracteristiques` (Array).

4. **`sensors`** :
   - `_id` (String), `machineId` (String), `type` (String), `unit` (String), `status` (String), `lastSeen` (Date).

5. **`sensor_readings`** :
   - `_id` (String), `machineId`, `sensorId`, `sensorType`, `value` (Double), `unit`, `timestamp` (Date), `receivedAt` (Date).

6. **`alerts`** :
   - `_id` (String), `machineId`, `sensorId`, `sensorType`, `severity`, `value`, `threshold`, `status`, `message`, `timestamp`.

---

### 2. Migration et Préservation des Données Existantes
Le schéma est rétrocompatible à 100% avec les bases de données existantes :
- Pour les utilisateurs existants dans la collection `users`, l'absence des champs `customGrantedPermissions` ou `customRevokedPermissions` est interprétée comme une liste vide. L'utilisateur hérite donc automatiquement de 100% des privilèges de son rôle d'origine.
- Pour les machines existantes, les seuils persistés sont créés automatiquement par `DataInitializer` ou à la première requête sans écraser les données télémétriques ou les configurations machine.
- Aucune table, collection ou index existant n'a été altéré ou supprimé.

---

## G. Guide d'Installation, Déploiement et Vérification

### 1. Prérequis
- Java JDK 17 ou supérieur (JDK 21 ou 25 recommandés).
- MongoDB Community Server (port par défaut `27017`).
- Broker MQTT (Mosquitto sur `localhost:1883`).
- Maven (inclus via le wrapper `./mvnw` ou `mvnw.cmd`).

### 2. Configuration des Variables d'Environnement
Dans `application.yml` ou via des variables d'environnement système :
```yaml
SERVER_PORT: 8080
MONGODB_URI: mongodb://localhost:27017/smartfactory
MQTT_BROKER_URL: tcp://localhost:1883
MQTT_TOPIC: factory/machines/#
JWT_SECRET: 404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970337336763979244226452948404D6351655468576D5A7134743777217A25432A
```

### 3. Démarrage du Backend
```powershell
# Depuis la racine du dossier BackEnd :
.\mvnw.cmd spring-boot:run
```

Au démarrage :
- L'administrateur par défaut est vérifié ou initialisé : `admin@smartfactory.com` / `adminpassword123`.
- La machine par défaut `CNC-024` et ses seuils dans MongoDB sont initialisés automatiquement.

### 4. Exécution des Tests Automatisés
```powershell
# Exécuter l'ensemble de la suite de tests (217 tests couvrant Sprints 1 à 7) :
.\mvnw.cmd test
```

Résultat attendu :
```
[INFO] Results:
[INFO] Tests run: 217, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

---

## H. Limites, Compatibilité et Synthèse

1. **Compatibilité Simulateur IoT (`iot-simulator/simulator.py`)** :
   - Le simulateur Python fonctionne nativement sans modification.
   - Les requêtes `--from-db` continuent d'interroger `GET /api/machines?page=0&size=200` et reçoivent la structure JSON exacte attendue.
   - Les trames MQTT publiées sur `factory/machines/{code}/{type}` sont ingérées, validées et évaluées par rapport aux seuils dynamiques chargés en mémoire.
2. **Intégrité de l'environnement** :
   - Aucun fichier du `frontend`, de la documentation racine ou des modules tiers n'a été altéré.
   - Seuls les fichiers du répertoire `BackEnd` ont été modifiés ou créés.
3. **Sécurité et Robustesse** :
   - Élimination complète des failles d'élévation de privilèges (un utilisateur ordinaire ne peut pas modifier ses propres privilèges).
   - Invalidation et gestion du cache thread-safe (`ConcurrentHashMap`).
   - Tolérance aux pannes : en cas de seuil manquant, un comportement sûr et traçable est appliqué sans interruption de service.
