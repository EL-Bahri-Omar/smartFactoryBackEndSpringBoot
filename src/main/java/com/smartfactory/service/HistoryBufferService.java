package com.smartfactory.service;

import com.smartfactory.entity.SensorReading;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service de mise en mémoire tampon (Buffer) et d'écriture par lots (Batch) (Partie B).
 * Règles métier :
 * - Fréquence de vidage par défaut : 20 secondes.
 * - Écritures groupées par lots dans la base dédiée SmartFactoryHistory.
 * - Traitement asynchrone non bloquant pour la réception MQTT et la diffusion WebSocket.
 * - Capacité bornée avec politique de dépassement explicite et observable.
 * - Réessai automatique en cas d'échec temporaire sans doublons évitables.
 * - Vidage propre à l'arrêt du backend (@PreDestroy).
 */
@Service
public class HistoryBufferService {

    private static final Logger log = LoggerFactory.getLogger(HistoryBufferService.class);

    private final MongoTemplate historyMongoTemplate;

    @Value("${history.batch.buffer-capacity:10000}")
    private int bufferCapacity = 10000;

    @Value("${history.batch.immediate-persist:false}")
    private boolean immediatePersist = false;

    @Value("${history.batch.max-retry-attempts:3}")
    private int maxRetryAttempts = 3;

    private final ConcurrentLinkedQueue<SensorReading> buffer = new ConcurrentLinkedQueue<>();
    private final AtomicInteger bufferSize = new AtomicInteger(0);
    private final AtomicBoolean isFlushing = new AtomicBoolean(false);

    // Métriques et observabilité
    private final AtomicLong totalEnqueuedCount = new AtomicLong(0);
    private final AtomicLong totalPersistedCount = new AtomicLong(0);
    private final AtomicLong totalDroppedCount = new AtomicLong(0);
    private final AtomicLong totalFailedBatches = new AtomicLong(0);
    private volatile Instant lastFlushTimestamp = Instant.now();
    private volatile long lastFlushDurationMs = 0;

    public HistoryBufferService(@Qualifier("historyMongoTemplate") MongoTemplate historyMongoTemplate) {
        this.historyMongoTemplate = historyMongoTemplate;
    }

    @PostConstruct
    public void initIndexes() {
        try {
            IndexOperations indexOps = historyMongoTemplate.indexOps(SensorReading.class);
            indexOps.ensureIndex(new CompoundIndexDefinition(new Document("machineId", 1).append("timestamp", -1)).named("history_machine_ts_idx"));
            indexOps.ensureIndex(new CompoundIndexDefinition(new Document("machineCode", 1).append("timestamp", -1)).named("history_machineCode_ts_idx"));
            indexOps.ensureIndex(new CompoundIndexDefinition(new Document("machineId", 1).append("sensorType", 1).append("timestamp", -1)).named("history_machine_type_ts_idx"));
            indexOps.ensureIndex(new CompoundIndexDefinition(new Document("machineCode", 1).append("sensorType", 1).append("timestamp", -1)).named("history_machineCode_type_ts_idx"));
            indexOps.ensureIndex(new CompoundIndexDefinition(new Document("machineId", 1).append("sensorId", 1).append("timestamp", 1)).named("history_dedup_idx"));
            log.info("Index historiques initialisés avec succès sur SmartFactoryHistory.sensor_readings");
        } catch (Exception ex) {
            log.warn("Initialisation différée des index de SmartFactoryHistory: {}", ex.getMessage());
        }
    }

    /**
     * Ajoute une mesure validée dans le buffer mémoire.
     * En cas de saturation du buffer, applique une politique explicite :
     * suppression de la mesure la plus ancienne avec journalisation et incrémentation de métrique.
     */
    public boolean enqueue(SensorReading reading) {
        if (reading == null) {
            return false;
        }

        // Contrôle de la taille maximale du buffer (Règle B2.11 & B2.12)
        if (bufferSize.get() >= bufferCapacity) {
            SensorReading dropped = buffer.poll();
            if (dropped != null) {
                bufferSize.decrementAndGet();
                totalDroppedCount.incrementAndGet();
                log.warn("ALERTE BUFFER SATURÉ: Capacité maximale [{}] atteinte. Éviction de la mesure la plus ancienne pour préserver la mémoire (total évincé: {}).",
                        bufferCapacity, totalDroppedCount.get());
            }
        }

        buffer.offer(reading);
        bufferSize.incrementAndGet();
        totalEnqueuedCount.incrementAndGet();

        // Si le mode immédiat est configuré (tests unitaires ou validation synchrone)
        if (immediatePersist) {
            flushBuffer();
        }

        return true;
    }

    /**
     * Tâche planifiée exécutée toutes les 20 secondes par défaut pour vider le buffer par lots.
     * Règle B2.1 : délai de sauvegarde de 20 secondes par défaut.
     */
    @Scheduled(fixedDelayString = "${history.batch.flush-interval-ms:20000}", initialDelay = 5000)
    public int flushScheduled() {
        return flushBuffer();
    }

    /**
     * Effectue le vidage par lots du buffer vers la base MongoDB dédiée SmartFactoryHistory.
     */
    public int flushBuffer() {
        if (buffer.isEmpty()) {
            return 0;
        }

        // Empêche les exécutions concurrentes multiples du flush
        if (!isFlushing.compareAndSet(false, true)) {
            log.debug("Un cycle d'écriture par lots est déjà en cours d'exécution. Flush reporté.");
            return 0;
        }

        long startTime = System.currentTimeMillis();
        List<SensorReading> batch = new ArrayList<>();

        try {
            // Dépiler les mesures du buffer dans le lot courant
            SensorReading item;
            while ((item = buffer.poll()) != null) {
                batch.add(item);
                bufferSize.decrementAndGet();
            }

            if (batch.isEmpty()) {
                return 0;
            }

            log.info("Début du cycle de persistance par lots vers SmartFactoryHistory: {} mesure(s) à sauvegarder...", batch.size());

            // Insertion groupée dans SmartFactoryHistory
            historyMongoTemplate.insert(batch, SensorReading.class);

            totalPersistedCount.addAndGet(batch.size());
            lastFlushTimestamp = Instant.now();
            lastFlushDurationMs = System.currentTimeMillis() - startTime;

            log.info("Batch MongoDB réussi: {} mesures sauvegardées dans SmartFactoryHistory en {} ms (buffer restant: {})",
                    batch.size(), lastFlushDurationMs, bufferSize.get());

            return batch.size();

        } catch (Exception ex) {
            totalFailedBatches.incrementAndGet();
            log.error("ÉCHEC DE LA SAUVEGARDE PAR LOTS dans SmartFactoryHistory ({} mesures): {}. Réessai programmé.",
                    batch.size(), ex.getMessage());

            // Réinsertion dans le buffer pour retry des éléments éligibles
            for (SensorReading failedItem : batch) {
                if (failedItem.incrementRetryCount() <= maxRetryAttempts) {
                    buffer.offer(failedItem);
                    bufferSize.incrementAndGet();
                } else {
                    totalDroppedCount.incrementAndGet();
                    log.error("Mesure abandonnée après {} tentatives d'écriture infructueuses: machine={}, sensor={}, ts={}",
                            maxRetryAttempts, failedItem.getMachineId(), failedItem.getSensorType(), failedItem.getTimestamp());
                }
            }
            return 0;

        } finally {
            isFlushing.set(false);
        }
    }

    /**
     * Fermeture propre du backend : tentative de persistance des mesures en attente (Règle B2.7).
     */
    @PreDestroy
    public void onShutdown() {
        int pending = bufferSize.get();
        if (pending > 0) {
            log.info("Arrêt de l'application détecté. Tentative de sauvegarde immédiate des {} mesures restantes en buffer...", pending);
            flushBuffer();
        }
    }

    // Accesseurs de surveillance et de configuration
    public int getBufferSize() {
        return bufferSize.get();
    }

    public long getTotalEnqueuedCount() {
        return totalEnqueuedCount.get();
    }

    public long getTotalPersistedCount() {
        return totalPersistedCount.get();
    }

    public long getTotalDroppedCount() {
        return totalDroppedCount.get();
    }

    public long getTotalFailedBatches() {
        return totalFailedBatches.get();
    }

    public Instant getLastFlushTimestamp() {
        return lastFlushTimestamp;
    }

    public long getLastFlushDurationMs() {
        return lastFlushDurationMs;
    }

    public void setImmediatePersist(boolean immediatePersist) {
        this.immediatePersist = immediatePersist;
    }

    public boolean isImmediatePersist() {
        return immediatePersist;
    }

    public void setBufferCapacity(int bufferCapacity) {
        this.bufferCapacity = bufferCapacity;
    }

    public int getBufferCapacity() {
        return bufferCapacity;
    }
}
