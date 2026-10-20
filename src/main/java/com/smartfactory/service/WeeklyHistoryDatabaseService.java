package com.smartfactory.service;

import com.mongodb.client.MongoClient;
import com.smartfactory.config.MongoConfig;
import com.smartfactory.entity.SensorReading;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service de gestion dynamique des bases de données MongoDB hebdomadaires pour les mesures simulées (Partie 2.2).
 *
 * Fonctionnalités :
 * 1. Rotation hebdomadaire automatique au format SmartFactory_History_YYYY_Www (ex: SmartFactory_History_2026_W43).
 * 2. Création automatique de la base et des index optimisés lors de la première écriture.
 * 3. Partage du pool de connexions MongoClient existant sans multiplier les sockets.
 * 4. Recherche transparente des mesures historiques sur plusieurs bases hebdomadaires selon la plage temporelle demandée.
 * 5. Politique de rétention stricte de 3 mois (configurable) avec mode simulation (dry-run) et protections anti-suppression accidentelle.
 */
@Service
public class WeeklyHistoryDatabaseService {

    private static final Logger log = LoggerFactory.getLogger(WeeklyHistoryDatabaseService.class);

    public static final String DB_NAME_PREFIX = "SmartFactory_History_";
    public static final Pattern WEEKLY_DB_PATTERN = Pattern.compile("^SmartFactory_History_(\\d{4})_W(\\d{2})$");

    private final MongoClient mongoClient;
    private final MongoConfig mongoConfig;
    private final MongoCustomConversions conversions;

    @Value("${history.retention.months:3}")
    private int retentionMonths = 3;

    @Value("${history.retention.auto-cleanup-enabled:true}")
    private boolean autoCleanupEnabled = true;

    @Value("${history.retention.dry-run:false}")
    private boolean retentionDryRun = false;

    // Cache des MongoTemplate par nom de base hebdomadaire
    private final ConcurrentHashMap<String, MongoTemplate> templateCache = new ConcurrentHashMap<>();

    public WeeklyHistoryDatabaseService(MongoClient mongoClient, MongoConfig mongoConfig, MongoCustomConversions conversions) {
        this.mongoClient = mongoClient;
        this.mongoConfig = mongoConfig;
        this.conversions = conversions;
    }

    /**
     * Calcule le nom de la base hebdomadaire pour un instant donné selon la norme ISO-8601.
     * Exemple : pour le 20 octobre 2026 -> SmartFactory_History_2026_W43.
     */
    public String getDatabaseNameForInstant(Instant instant) {
        Instant effective = instant != null ? instant : Instant.now();
        ZonedDateTime zdt = effective.atZone(ZoneOffset.UTC);
        int year = zdt.get(WeekFields.ISO.weekBasedYear());
        int week = zdt.get(WeekFields.ISO.weekOfWeekBasedYear());
        return String.format("%s%04d_W%02d", DB_NAME_PREFIX, year, week);
    }

    /**
     * Retourne le nom de la base historique pour la semaine courante.
     */
    public String getCurrentWeeklyDatabaseName() {
        return getDatabaseNameForInstant(Instant.now());
    }

    /**
     * Obtient le MongoTemplate pour la semaine courante.
     */
    public MongoTemplate getCurrentWeeklyMongoTemplate() {
        return getMongoTemplateForDatabase(getCurrentWeeklyDatabaseName());
    }

    /**
     * Récupère ou instancie un MongoTemplate pour une base hebdomadaire spécifique,
     * en réutilisant le MongoClient sans créer de nouvelle connexion réseau.
     */
    public MongoTemplate getMongoTemplateForDatabase(String databaseName) {
        if (databaseName == null || databaseName.isBlank()) {
            databaseName = getCurrentWeeklyDatabaseName();
        }
        return templateCache.computeIfAbsent(databaseName, this::createWeeklyMongoTemplate);
    }

    private MongoTemplate createWeeklyMongoTemplate(String dbName) {
        log.info("Sélection/Création de la base hebdomadaire MongoDB : {}", dbName);
        SimpleMongoClientDatabaseFactory factory = new SimpleMongoClientDatabaseFactory(mongoClient, dbName);
        MongoTemplate template = new MongoTemplate(factory, mongoConfig.createPublicMappingConverter(factory, conversions));
        ensureWeeklyIndexes(template, dbName);
        return template;
    }

    /**
     * Initialise les index compacts indispensables sur la collection sensor_readings de la base hebdomadaire.
     */
    private void ensureWeeklyIndexes(MongoTemplate template, String dbName) {
        try {
            IndexOperations ops = template.indexOps("sensor_readings");
            ops.ensureIndex(new CompoundIndexDefinition(new Document("sensorId", 1).append("timestamp", -1)).named("sensorId_ts_idx"));
            ops.ensureIndex(new CompoundIndexDefinition(new Document("sensorId", 1).append("sensorType", 1).append("timestamp", -1)).named("sensorId_type_ts_idx"));
            ops.ensureIndex(new CompoundIndexDefinition(new Document("timestamp", -1)).named("ts_idx"));
            log.debug("Index compacts initialisés sur {}.sensor_readings", dbName);
        } catch (Exception ex) {
            log.warn("Création différée des index pour {} : {}", dbName, ex.getMessage());
        }
    }

    /**
     * Détermine l'ensemble ordonné des bases de données hebdomadaires couvrant un intervalle temporel [from, to].
     */
    public Set<String> getDatabaseNamesForRange(Instant from, Instant to) {
        Set<String> result = new LinkedHashSet<>();
        Instant now = Instant.now();

        Instant start = from != null ? from : now.minus(7, ChronoUnit.DAYS);
        Instant end = to != null ? to : now;

        if (start.isAfter(end)) {
            Instant temp = start;
            start = end;
            end = temp;
        }

        ZonedDateTime cursor = start.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime limit = end.atZone(ZoneOffset.UTC);

        while (!cursor.isAfter(limit)) {
            result.add(getDatabaseNameForInstant(cursor.toInstant()));
            cursor = cursor.plusDays(3); // Échantillonne tous les 3 jours pour capturer chaque transition de semaine
        }
        result.add(getDatabaseNameForInstant(end));

        return result;
    }

    /**
     * Liste toutes les bases de données existantes dans MongoDB correspondant au format des bases hebdomadaires.
     */
    public List<String> listExistingWeeklyDatabases() {
        List<String> list = new ArrayList<>();
        try {
            for (String name : mongoClient.listDatabaseNames()) {
                if (WEEKLY_DB_PATTERN.matcher(name).matches()) {
                    list.add(name);
                }
            }
            Collections.sort(list);
        } catch (Exception ex) {
            log.error("Erreur lors du listage des bases MongoDB : {}", ex.getMessage());
        }
        return list;
    }

    /**
     * Mode simulation de la politique de rétention de 3 mois (dry-run).
     * Retourne la liste des bases expirées qui seraient supprimées sans effectuer la suppression.
     */
    public List<String> simulateRetentionCleanup() {
        return executeRetentionCleanupInternal(true);
    }

    /**
     * Exécute la politique de rétention en supprimant réellement les bases expirées.
     */
    public List<String> executeRetentionCleanup(boolean dryRun) {
        return executeRetentionCleanupInternal(dryRun);
    }

    /**
     * Tâche planifiée automatique de nettoyage des bases hebdomadaires de mesures simulées.
     * Exécutée quotidiennement à 03h00 du matin.
     */
    @Scheduled(cron = "${history.retention.cron:0 0 3 * * ?}")
    public void scheduledRetentionCleanup() {
        if (!autoCleanupEnabled) {
            log.info("Nettoyage automatique de rétention désactivé par configuration (history.retention.auto-cleanup-enabled=false)");
            return;
        }
        log.info("Démarrage de la tâche planifiée de rétention historique (durée max: {} mois, dryRun: {})...", retentionMonths, retentionDryRun);
        executeRetentionCleanupInternal(retentionDryRun);
    }

    private List<String> executeRetentionCleanupInternal(boolean dryRun) {
        List<String> existing = listExistingWeeklyDatabases();
        List<String> toDelete = new ArrayList<>();
        String currentDbName = getCurrentWeeklyDatabaseName();

        long retentionDays = (long) retentionMonths * 30L;
        Instant retentionThreshold = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

        log.info("Vérification de rétention : seuil de conservation = {} jours (avant {}). Bases trouvées : {}",
                retentionDays, retentionThreshold, existing.size());

        for (String dbName : existing) {
            // SÉCURITÉ ABSOLUE 1 : Ne JAMAIS toucher à la base en cours d'utilisation
            if (dbName.equalsIgnoreCase(currentDbName)) {
                log.debug("Base hebdomadaire en cours d'utilisation [{}], conservation obligatoire.", dbName);
                continue;
            }

            // SÉCURITÉ ABSOLUE 2 : Ne JAMAIS supprimer la base opérationnelle ni la base générique
            if ("smartfactory".equalsIgnoreCase(dbName) || "SmartFactoryHistory".equalsIgnoreCase(dbName)) {
                log.warn("TENTATIVE DE SUPPRESSION BLOQUÉE pour la base protégée : {}", dbName);
                continue;
            }

            // SÉCURITÉ ABSOLUE 3 : Vérifier le motif regex strict
            Matcher matcher = WEEKLY_DB_PATTERN.matcher(dbName);
            if (!matcher.matches()) {
                log.warn("Nom de base non géré ignoré par sécurité : {}", dbName);
                continue;
            }

            int year = Integer.parseInt(matcher.group(1));
            int week = Integer.parseInt(matcher.group(2));

            // Fin de la semaine considérée
            LocalDate endOfWeekDate = LocalDate.of(year, 1, 1)
                    .with(WeekFields.ISO.weekOfWeekBasedYear(), week)
                    .with(WeekFields.ISO.dayOfWeek(), 7);
            Instant endOfWeek = endOfWeekDate.atTime(23, 59, 59).atZone(ZoneOffset.UTC).toInstant();

            if (endOfWeek.isBefore(retentionThreshold)) {
                toDelete.add(dbName);
                long ageDays = ChronoUnit.DAYS.between(endOfWeek, Instant.now());
                if (dryRun) {
                    log.info("[SIMULATION RÉTENTION] La base hebdomadaire '{}' (âge: {} jours) SERAIT supprimée.", dbName, ageDays);
                } else {
                    try {
                        log.warn("[ACTION RÉTENTION] Suppression de la base historique expirée '{}' (âge: {} jours)...", dbName, ageDays);
                        mongoClient.getDatabase(dbName).drop();
                        templateCache.remove(dbName);
                        log.info("Base '{}' supprimée avec succès.", dbName);
                    } catch (Exception ex) {
                        log.error("Échec de la suppression de la base expirée '{}' : {}", dbName, ex.getMessage());
                    }
                }
            }
        }

        log.info("Cycle de rétention terminé : {} base(s) {} sur {} examinée(s).",
                toDelete.size(), dryRun ? "identifiée(s) pour suppression" : "supprimée(s)", existing.size());

        return toDelete;
    }

    /**
     * Recherche les mesures historiques dans les bases hebdomadaires correspondant à une plage temporelle.
     */
    public List<SensorReading> findReadingsInWeeklyDatabases(List<String> sensorIds, com.smartfactory.entity.SensorType sensorType, Instant from, Instant to, org.springframework.data.domain.Sort sort, int limit) {
        if (sensorIds == null || sensorIds.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> rangeDatabases = getDatabaseNamesForRange(from, to);
        List<String> existingWeekly = listExistingWeeklyDatabases();
        List<SensorReading> combined = new ArrayList<>();

        for (String dbName : rangeDatabases) {
            if (!existingWeekly.contains(dbName)) {
                continue;
            }
            try {
                MongoTemplate template = getMongoTemplateForDatabase(dbName);
                org.springframework.data.mongodb.core.query.Query query = new org.springframework.data.mongodb.core.query.Query();
                org.springframework.data.mongodb.core.query.Criteria criteria = org.springframework.data.mongodb.core.query.Criteria.where("sensorId").in(sensorIds);

                if (sensorType != null) {
                    criteria = criteria.and("sensorType").is(sensorType);
                }
                if (from != null && to != null) {
                    criteria = criteria.and("timestamp").gte(from).lte(to);
                } else if (from != null) {
                    criteria = criteria.and("timestamp").gte(from);
                } else if (to != null) {
                    criteria = criteria.and("timestamp").lte(to);
                }

                query.addCriteria(criteria);
                if (sort != null) {
                    query.with(sort);
                }
                if (limit > 0) {
                    query.limit(limit);
                }

                List<SensorReading> readings = template.find(query, SensorReading.class, "sensor_readings");
                combined.addAll(readings);
            } catch (Exception ex) {
                log.warn("Erreur lors de la lecture dans la base hebdomadaire {} : {}", dbName, ex.getMessage());
            }
        }

        return combined;
    }

    public int getRetentionMonths() {
        return retentionMonths;
    }

    public void setRetentionMonths(int retentionMonths) {
        this.retentionMonths = retentionMonths;
    }

    public boolean isAutoCleanupEnabled() {
        return autoCleanupEnabled;
    }

    public void setAutoCleanupEnabled(boolean autoCleanupEnabled) {
        this.autoCleanupEnabled = autoCleanupEnabled;
    }
}
