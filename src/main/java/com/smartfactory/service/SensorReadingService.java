package com.smartfactory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfactory.entity.*;
import com.smartfactory.repository.AlertRepository;
import com.smartfactory.repository.MachineRepository;
import com.smartfactory.repository.SensorReadingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Service de traitement des mesures capteurs, télémétrie temps réel et historique.
 * Implémente :
 * - Partie B : buffer de persistance et sauvegarde MongoDB toutes les 20s.
 * - US20 à US23 : lecture temps réel (température, vibration, courant, RPM) avec seuils.
 * - US24 : états machine et détection d'inactivité.
 * - US25 : diffusion STOMP temps réel immédiate (sans attendre les 20s).
 * - US26 à US28 : historique, séries temporelles et filtrage par période.
 */
@Service
public class SensorReadingService {

    private static final Logger log = LoggerFactory.getLogger(SensorReadingService.class);

    private final SensorReadingRepository readingRepository;
    private final MachineRepository machineRepository;
    private final SensorService sensorService;
    private final AlertRepository alertRepository;
    private final RealtimeMessageService realtimeMessageService;
    private final ObjectMapper objectMapper;
    private final MachineThresholdService machineThresholdService;
    private final HistoryBufferService historyBufferService;
    private final TelemetryCacheService telemetryCacheService;
    private final AnomalyService anomalyService;
    private final AlertService alertService;
    private final WeeklyHistoryDatabaseService weeklyHistoryDatabaseService;

    public SensorReadingService(SensorReadingRepository readingRepository,
                                MachineRepository machineRepository,
                                SensorService sensorService,
                                AlertRepository alertRepository,
                                RealtimeMessageService realtimeMessageService,
                                ObjectMapper objectMapper,
                                MachineThresholdService machineThresholdService,
                                HistoryBufferService historyBufferService,
                                TelemetryCacheService telemetryCacheService,
                                AnomalyService anomalyService,
                                AlertService alertService,
                                WeeklyHistoryDatabaseService weeklyHistoryDatabaseService) {
        this.readingRepository = readingRepository;
        this.machineRepository = machineRepository;
        this.sensorService = sensorService;
        this.alertRepository = alertRepository;
        this.realtimeMessageService = realtimeMessageService;
        this.objectMapper = objectMapper;
        this.machineThresholdService = machineThresholdService;
        this.historyBufferService = historyBufferService;
        this.telemetryCacheService = telemetryCacheService;
        this.anomalyService = anomalyService;
        this.alertService = alertService;
        this.weeklyHistoryDatabaseService = weeklyHistoryDatabaseService;
    }

    /**
     * Reçoit, valide, diffuse en temps réel (WebSocket) et met en mémoire tampon pour
     * la persistance différée (20 secondes) vers SmartFactoryHistory.
     */
    public SensorReading validateAndProcess(String topic, String rawPayload) {
        log.debug("Processing MQTT message on topic [{}]: {}", topic, rawPayload);

        if (!StringUtils.hasText(rawPayload)) {
            log.warn("Rejected MQTT message: empty payload on topic [{}]", topic);
            throw new IllegalArgumentException("Empty MQTT payload");
        }

        // 1. Parse JSON
        JsonNode root;
        try {
            root = objectMapper.readTree(rawPayload);
        } catch (Exception e) {
            log.warn("MQTT - Message invalide\nTopic: {}\nReason: JSON invalide", topic);
            throw new IllegalArgumentException("Invalid JSON payload: " + e.getMessage(), e);
        }

        // 2. Extraire les composants du topic (factory/machines/{machineId}/{sensorType})
        String topicMachineCode = null;
        String topicSensorStr = null;
        if (StringUtils.hasText(topic)) {
            String[] parts = topic.split("/");
            if (parts.length >= 4 && "factory".equals(parts[0]) && "machines".equals(parts[1])) {
                topicMachineCode = parts[2];
                topicSensorStr = parts[3];
            }
        }

        // 3. Identification de la machine
        String payloadMachineCode = root.path("machineCode").asText(null);
        String payloadMachineId = root.path("machineId").asText(null);
        String effectivePayloadIdentifier = StringUtils.hasText(payloadMachineCode) ? payloadMachineCode : payloadMachineId;
        String machineLookupKey = StringUtils.hasText(effectivePayloadIdentifier) ? effectivePayloadIdentifier : topicMachineCode;

        if (!StringUtils.hasText(machineLookupKey)) {
            log.warn("MQTT - Message invalide\nTopic: {}\nReason: Identifiant machine manquant", topic);
            throw new IllegalArgumentException("Machine identifier is required");
        }

        if (StringUtils.hasText(topicMachineCode) && StringUtils.hasText(payloadMachineCode)
                && !topicMachineCode.equalsIgnoreCase(payloadMachineCode)) {
            log.warn("MQTT - Message invalide\nTopic: {}\nReason: Code machine incohérent entre topic [{}] et payload [{}]",
                    topic, topicMachineCode, payloadMachineCode);
            throw new IllegalArgumentException("Machine code mismatch between topic and payload");
        }

        Machine machine = findMachineByCodeOrId(machineLookupKey).orElse(null);
        if (machine == null && StringUtils.hasText(topicMachineCode)) {
            machine = findMachineByCodeOrId(topicMachineCode).orElse(null);
        }

        if (machine == null) {
            log.warn("MQTT - Message invalide\nTopic: {}\nReason: Machine inconnue [{}]", topic, machineLookupKey);
            throw new IllegalArgumentException("Unknown machine: " + machineLookupKey);
        }

        // 4. Validation du type de capteur
        String payloadSensorTypeStr = root.path("sensorType").asText(null);
        SensorType payloadSensorType = SensorType.fromString(payloadSensorTypeStr);
        SensorType topicSensorType = SensorType.fromString(topicSensorStr);

        SensorType effectiveSensorType = payloadSensorType != null ? payloadSensorType : topicSensorType;
        if (effectiveSensorType == null) {
            log.warn("MQTT - Message invalide\nTopic: {}\nReason: Type de capteur invalide", topic);
            throw new IllegalArgumentException("Invalid sensor type");
        }

        if (payloadSensorType != null && topicSensorType != null && payloadSensorType != topicSensorType) {
            log.warn("MQTT - Message invalide\nTopic: {}\nReason: Discordance de type de capteur entre topic [{}] et payload [{}]",
                    topic, topicSensorType, payloadSensorType);
            throw new IllegalArgumentException("Topic sensor type mismatch with payload");
        }

        // 5. Validation de la valeur mesurée
        JsonNode valueNode = root.get("value");
        if (valueNode == null || !valueNode.isNumber()) {
            log.warn("MQTT - Message invalide\nTopic: {}\nReason: Valeur numérique manquante ou invalide", topic);
            throw new IllegalArgumentException("Value must be a valid number");
        }
        double value = valueNode.asDouble();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            log.warn("MQTT - Message invalide\nTopic: {}\nReason: Valeur non numérique (NaN ou Infinite)", topic);
            throw new IllegalArgumentException("Value cannot be NaN or Infinite");
        }

        // 6. Validation de l'unité
        String unit = root.path("unit").asText(null);
        if (!StringUtils.hasText(unit)) {
            unit = effectiveSensorType.getDefaultUnit();
        } else if (!effectiveSensorType.isValidUnit(unit)) {
            log.warn("MQTT - Message invalide\nTopic: {}\nReason: Unité invalide [{}] pour {}", topic, unit, effectiveSensorType);
            throw new IllegalArgumentException("Invalid unit [" + unit + "] for sensor type " + effectiveSensorType);
        }

        // 7. Validation de l'horodatage
        String timestampStr = root.path("timestamp").asText(null);
        Instant timestamp = parseTimestamp(timestampStr);
        if (timestamp == null) {
            log.warn("MQTT - Message invalide\nTopic: {}\nReason: Format de timestamp invalide [{}]", topic, timestampStr);
            throw new IllegalArgumentException("Invalid timestamp: " + timestampStr);
        }

        Instant now = Instant.now();
        if (timestamp.isAfter(now.plus(24, ChronoUnit.HOURS))) {
            log.warn("MQTT - Message invalide\nTopic: {}\nReason: Timestamp dans le futur [{}]", topic, timestamp);
            throw new IllegalArgumentException("Timestamp is in the future");
        }
        if (timestamp.isBefore(now.minus(30, ChronoUnit.DAYS))) {
            log.warn("MQTT - Message invalide\nTopic: {}\nReason: Timestamp trop ancien [{}] (> 30 jours)", topic, timestamp);
            throw new IllegalArgumentException("Timestamp is too old (> 30 days)");
        }

        // 8. Auto-provisioning & validation du capteur
        Sensor sensor = sensorService.getOrCreateSensor(machine, effectiveSensorType, unit);
        if (!machine.getId().equals(sensor.getMachineId())) {
            log.warn("MQTT - Message invalide\nTopic: {}\nReason: Sensor [{}] does not belong to machine [{}]", topic, sensor.getId(), machine.getId());
            throw new IllegalArgumentException("Sensor does not belong to machine");
        }

        // 9. Vérification anti-doublon
        if (readingRepository.existsByMachineIdAndSensorIdAndTimestamp(machine.getId(), sensor.getId(), timestamp)) {
            log.debug("Duplicate measurement ignored for machine [{}] sensor [{}] timestamp [{}]", machine.getCode(), sensor.getId(), timestamp);
            return null;
        }

        // 10. Construction du document de mesure
        SensorReading reading = new SensorReading(
                machine.getId(),
                machine.getCode(),
                sensor.getId(),
                effectiveSensorType,
                value,
                unit,
                timestamp,
                now
        );

        // Évaluation des seuils configurés en base
        MachineThreshold.EvaluationResult evaluation = machineThresholdService.evaluateReading(machine, effectiveSensorType, value);
        if (evaluation != null) {
            reading.getMetadata().put("thresholdStatus", evaluation.getStatus());
            reading.getMetadata().put("thresholdSeverity", evaluation.getSeverity());
            if (evaluation.getBreachedThreshold() != null) {
                reading.getMetadata().put("breachedThreshold", evaluation.getBreachedThreshold());
            }
        }

        // 11. Actualisation immédiate du cache temps réel (US20 à US23)
        telemetryCacheService.updateLatestReading(reading, machine.getCode());

        // Récupération automatique si machine OFFLINE -> RUNNING (US24)
        if (machine.getStatus() == MachineStatus.OFFLINE) {
            machine.setStatus(MachineStatus.RUNNING);
            machineRepository.save(machine);
            telemetryCacheService.setMachineStatus(machine.getId(), MachineStatus.RUNNING);
            telemetryCacheService.setMachineStatus(machine.getCode(), MachineStatus.RUNNING);
            log.info("Machine [{}] a reçu une nouvelle mesure -> transition automatique OFFLINE -> RUNNING", machine.getCode());
            realtimeMessageService.broadcastMachineStatus(machine);
        }

        // Journalisation structurée conforme
        log.info("MQTT - Message reçu\nTopic: {}\nMachine: {}\nSensor: {}\nValue: {}\nUnit: {}\nTimestamp: {}\nStatus: RECEIVED",
                topic, machine.getCode(), effectiveSensorType, value, unit, timestampStr != null ? timestampStr : timestamp.toString());

        // 12. Actualisation de l'état du capteur (US19)
        sensor.setLastSeen(now);
        if (sensor.getStatus() == SensorStatus.OFFLINE) {
            sensor.setStatus(SensorStatus.ONLINE);
            sensorService.save(sensor);
            log.info("Sensor [{}] on machine [{}] transitioned OFFLINE -> ONLINE", sensor.getId(), machine.getCode());
            realtimeMessageService.broadcastSensorStatus(sensor, machine.getCode());
        } else {
            sensorService.save(sensor);
        }

        // 13. Gestion du cycle de vie des anomalies et transitions d'alertes (US33, US37, US38, US40)
        // Centralisation des transitions NORMAL -> WARNING -> CRITICAL -> NORMAL
        alertService.processSensorReadingTransition(
                machine,
                sensor,
                effectiveSensorType,
                value,
                evaluation,
                timestamp
        );

        // 14. Diffusion WebSocket IMMÉDIATE (US25) - Aucun retard de 20s
        realtimeMessageService.broadcastReading(reading, machine.getCode());

        // 15. Envoi dans le buffer mémoire pour la persistance par lots toutes les 20 secondes (Partie B)
        historyBufferService.enqueue(reading);

        return reading;
    }

    /**
     * Récupère la mesure courante d'un capteur pour une machine (US20, US21, US22, US23).
     */
    public Map<String, Object> getCurrentSensorReading(String machineIdOrCode, SensorType sensorType) {
        Machine machine = resolveMachine(machineIdOrCode);

        // 1. Chercher d'abord dans le cache temps réel O(1)
        SensorReading reading = telemetryCacheService.getLatestReading(machine.getId(), sensorType)
                .orElseGet(() -> telemetryCacheService.getLatestReading(machine.getCode(), sensorType)
                        .orElse(null));

        // 2. Si pas en cache (démarrage du backend), chercher la dernière mesure persistée
        if (reading == null) {
            Page<SensorReading> p = readingRepository.findByMachineIdAndSensorType(
                    machine.getId(), sensorType, PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "timestamp"))
            );
            if (p.hasContent()) {
                reading = p.getContent().get(0);
                telemetryCacheService.updateLatestReading(reading, machine.getCode());
            }
        }

        MachineThreshold threshold = machineThresholdService.getThresholdCached(machine, sensorType);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("machineId", machine.getId());
        res.put("machineCode", machine.getCode());
        res.put("sensorType", sensorType.name());

        if (reading != null) {
            res.put("value", reading.getValue());
            res.put("unit", reading.getUnit());
            res.put("timestamp", reading.getTimestamp().toString());

            MachineThreshold.EvaluationResult eval = threshold != null
                    ? threshold.evaluate(reading.getValue())
                    : MachineThreshold.EvaluationResult.safe();

            res.put("status", eval.getStatus());
            res.put("thresholdBreached", eval.isAlertTriggered());
            res.put("severity", eval.getSeverity());
            res.put("breachedThreshold", eval.getBreachedThreshold());
        } else {
            res.put("value", null);
            res.put("unit", sensorType.getDefaultUnit());
            res.put("timestamp", null);
            res.put("status", "NO_DATA");
            res.put("thresholdBreached", false);
            res.put("severity", "NONE");
            res.put("breachedThreshold", null);
        }

        if (threshold != null) {
            res.put("warningMin", threshold.getWarningMin());
            res.put("warningMax", threshold.getWarningMax());
            res.put("criticalMin", threshold.getCriticalMin());
            res.put("criticalMax", threshold.getCriticalMax());
        }

        return res;
    }

    /**
     * Résumé complet de télémétrie après reconnexion WebSocket (US25).
     */
    public Map<String, Object> getLatestTelemetrySummary(String machineIdOrCode) {
        Machine machine = resolveMachine(machineIdOrCode);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("machineId", machine.getId());
        res.put("machineCode", machine.getCode());
        res.put("status", machine.getStatus() != null ? machine.getStatus().name() : "IDLE");

        Map<String, Object> sensors = new LinkedHashMap<>();
        for (SensorType type : SensorType.values()) {
            sensors.put(type.name().toLowerCase(), getCurrentSensorReading(machine.getId(), type));
        }
        res.put("sensors", sensors);
        res.put("timestamp", Instant.now().toString());

        return res;
    }

    /**
     * Consultation de l'état de la machine (US24).
     */
    public Map<String, Object> getMachineStatusInfo(String machineIdOrCode) {
        Machine machine = resolveMachine(machineIdOrCode);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("machineId", machine.getId());
        res.put("machineCode", machine.getCode());
        res.put("name", machine.getName());
        res.put("status", machine.getStatus() != null ? machine.getStatus().name() : "IDLE");

        Optional<Instant> lastSeen = telemetryCacheService.getLastSeen(machine.getId());
        if (lastSeen.isEmpty()) {
            lastSeen = telemetryCacheService.getLastSeen(machine.getCode());
        }
        res.put("lastSeen", lastSeen.map(Instant::toString).orElse(null));
        res.put("isOnline", machine.getStatus() == MachineStatus.RUNNING || machine.getStatus() == MachineStatus.IDLE);
        return res;
    }

    /**
     * Mise à jour de l'état machine (US24).
     */
    public Machine updateMachineStatus(String machineIdOrCode, MachineStatus newStatus) {
        if (newStatus == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR: Le statut de la machine est obligatoire");
        }
        Machine machine = resolveMachine(machineIdOrCode);
        machine.setStatus(newStatus);
        Machine saved = machineRepository.save(machine);

        telemetryCacheService.setMachineStatus(machine.getId(), newStatus);
        telemetryCacheService.setMachineStatus(machine.getCode(), newStatus);
        realtimeMessageService.broadcastMachineStatus(saved);

        log.info("Statut machine mis à jour manuellement: [{}] -> {}", saved.getCode(), newStatus);
        return saved;
    }

    /**
     * Consultation de l'historique paginé dans la base dédiée SmartFactoryHistory (US26).
     * GET /api/machines/{id}/readings
     */
    public Map<String, Object> getReadings(String machineIdOrCode, Instant from, Instant to, SensorType sensorType, Pageable pageable) {
        Machine machine = resolveMachine(machineIdOrCode);

        // Validation des dates
        if (from != null && to != null && from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR: La date de début ('from') doit être antérieure à la date de fin ('to')");
        }

        String machineId = machine.getId();
        Page<SensorReading> page;

        if (from != null && to != null && sensorType != null) {
            page = readingRepository.findByMachineIdAndSensorTypeAndTimestampBetween(machineId, sensorType, from, to, pageable);
        } else if (from != null && to != null) {
            page = readingRepository.findByMachineIdAndTimestampBetween(machineId, from, to, pageable);
        } else if (sensorType != null) {
            page = readingRepository.findByMachineIdAndSensorType(machineId, sensorType, pageable);
        } else {
            page = readingRepository.findByMachineId(machineId, pageable);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", page.getContent());
        response.put("page", page.getNumber());
        response.put("size", page.getSize());
        response.put("totalElements", page.getTotalElements());
        response.put("totalPages", page.getTotalPages());
        return response;
    }

    /**
     * Séries temporelles pour affichage graphique avec filtrage par période (US27 & US28).
     */
    public Map<String, Object> getTimeseries(String machineIdOrCode, SensorType sensorType,
                                             String period, Instant from, Instant to,
                                             String aggregate, int maxPoints) {
        Machine machine = resolveMachine(machineIdOrCode);

        Instant[] bounds = resolvePeriodBounds(period, from, to);
        Instant effectiveFrom = bounds[0];
        Instant effectiveTo = bounds[1];

        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR: La date de début ('from') doit être antérieure à la date de fin ('to')");
        }

        int boundedMax = Math.max(10, Math.min(maxPoints, 2000));
        Sort sort = Sort.by(Sort.Direction.ASC, "timestamp");
        List<SensorReading> raw = fetchHistoricalReadings(machine, sensorType, effectiveFrom, effectiveTo, sort, boundedMax * 2);

        List<Map<String, Object>> points = new ArrayList<>();

        if (raw.size() <= boundedMax || "none".equalsIgnoreCase(aggregate)) {
            for (SensorReading r : raw) {
                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("timestamp", r.getTimestamp().toString());
                pt.put("value", r.getValue());
                pt.put("unit", r.getUnit());
                pt.put("sensorType", r.getSensorType() != null ? r.getSensorType().name() : null);
                points.add(pt);
            }
        } else {
            // Échantillonnage / agrégation préservant l'intégrité industrielle
            int step = (int) Math.ceil((double) raw.size() / boundedMax);
            for (int i = 0; i < raw.size(); i += step) {
                int end = Math.min(i + step, raw.size());
                List<SensorReading> window = raw.subList(i, end);

                double avg = window.stream().mapToDouble(SensorReading::getValue).average().orElse(0.0);
                SensorReading representative = window.get(window.size() / 2);

                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("timestamp", representative.getTimestamp().toString());
                pt.put("value", Math.round(avg * 100.0) / 100.0);
                pt.put("unit", representative.getUnit());
                pt.put("sensorType", representative.getSensorType() != null ? representative.getSensorType().name() : null);
                points.add(pt);
            }
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("machineId", machine.getId());
        res.put("machineCode", machine.getCode());
        res.put("sensorType", sensorType != null ? sensorType.name() : "ALL");
        res.put("from", effectiveFrom.toString());
        res.put("to", effectiveTo.toString());
        res.put("period", period);
        res.put("totalPoints", points.size());
        res.put("points", points);

        return res;
    }

    /**
     * Recherche les mesures dans les bases historiques (SmartFactoryHistory et bases hebdomadaires SmartFactory_History_YYYY_Www).
     */
    public List<SensorReading> fetchHistoricalReadings(Machine machine, SensorType sensorType, Instant from, Instant to, Sort sort, int limit) {
        Sort effectiveSort = sort != null ? sort : Sort.by(Sort.Direction.ASC, "timestamp");
        List<SensorReading> legacy;
        if (sensorType != null && from != null && to != null) {
            legacy = readingRepository.findByMachineIdAndSensorTypeAndTimestampBetween(machine.getId(), sensorType, from, to, effectiveSort);
        } else if (from != null && to != null) {
            legacy = readingRepository.findByMachineIdAndTimestampBetween(machine.getId(), from, to, effectiveSort);
        } else if (sensorType != null) {
            legacy = readingRepository.findByMachineIdAndSensorType(machine.getId(), sensorType, PageRequest.of(0, Math.max(limit, 500), effectiveSort)).getContent();
        } else {
            legacy = readingRepository.findByMachineId(machine.getId(), PageRequest.of(0, Math.max(limit, 500), effectiveSort)).getContent();
        }

        List<String> sensorIds = sensorService.getSensorsByMachine(machine.getId()).stream()
                .filter(s -> sensorType == null || s.getType() == sensorType)
                .map(Sensor::getId)
                .toList();

        List<SensorReading> weekly = weeklyHistoryDatabaseService.findReadingsInWeeklyDatabases(sensorIds, sensorType, from, to, effectiveSort, limit);

        for (SensorReading r : weekly) {
            if (r.getMachineId() == null) r.setMachineId(machine.getId());
            if (r.getMachineCode() == null) r.setMachineCode(machine.getCode());
        }

        if (weekly.isEmpty()) {
            return legacy;
        }

        Map<String, SensorReading> mergedMap = new LinkedHashMap<>();
        for (SensorReading r : legacy) {
            String key = r.getSensorId() + "_" + r.getTimestamp();
            mergedMap.put(key, r);
        }
        for (SensorReading r : weekly) {
            String key = r.getSensorId() + "_" + r.getTimestamp();
            mergedMap.putIfAbsent(key, r);
        }

        List<SensorReading> merged = new ArrayList<>(mergedMap.values());
        merged.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));
        return merged;
    }

    /**
     * Résolution des périodes rapides (US28) : 1h, 24h, 7d, 30d en horodatages UTC explicites.
     */
    public Instant[] resolvePeriodBounds(String period, Instant from, Instant to) {
        Instant now = Instant.now();
        if (StringUtils.hasText(period)) {
            switch (period.trim().toLowerCase()) {
                case "1h":
                    return new Instant[]{now.minus(1, ChronoUnit.HOURS), now};
                case "24h":
                    return new Instant[]{now.minus(24, ChronoUnit.HOURS), now};
                case "7d":
                    return new Instant[]{now.minus(7, ChronoUnit.DAYS), now};
                case "30d":
                    return new Instant[]{now.minus(30, ChronoUnit.DAYS), now};
                default:
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "VALIDATION_ERROR: Période non reconnue '" + period + "'. Valeurs valides: 1h, 24h, 7d, 30d");
            }
        }

        Instant effectiveFrom = from != null ? from : now.minus(24, ChronoUnit.HOURS);
        Instant effectiveTo = to != null ? to : now;
        return new Instant[]{effectiveFrom, effectiveTo};
    }

    public Machine resolveMachine(String machineIdOrCode) {
        return findMachineByCodeOrId(machineIdOrCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "MACHINE_NOT_FOUND: Machine not found with id: " + machineIdOrCode));
    }

    public Optional<Machine> findMachineByCodeOrId(String identifier) {
        if (!StringUtils.hasText(identifier)) {
            return Optional.empty();
        }
        Optional<Machine> byCode = machineRepository.findByCode(identifier.trim());
        if (byCode.isPresent()) {
            return byCode;
        }
        return machineRepository.findById(identifier.trim());
    }

    private Instant parseTimestamp(String ts) {
        if (!StringUtils.hasText(ts)) {
            return null;
        }
        try {
            return Instant.parse(ts.trim());
        } catch (DateTimeParseException ignored) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(ts.trim());
                return ldt.toInstant(ZoneOffset.UTC);
            } catch (Exception ex) {
                return null;
            }
        }
    }
}
