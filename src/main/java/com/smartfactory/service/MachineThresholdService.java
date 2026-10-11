package com.smartfactory.service;

import com.smartfactory.entity.Machine;
import com.smartfactory.entity.MachineThreshold;
import com.smartfactory.entity.SensorType;
import com.smartfactory.repository.MachineRepository;
import com.smartfactory.repository.MachineThresholdRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service de gestion et d'évaluation des seuils de surveillance des machines.
 * Intègre un cache en mémoire concurrent pour des performances optimales lors de la réception MQTT,
 * avec invalidation immédiate lors des modifications.
 */
@Service
public class MachineThresholdService {

    private static final Logger log = LoggerFactory.getLogger(MachineThresholdService.class);

    private final MachineThresholdRepository thresholdRepository;
    private final MachineRepository machineRepository;

    /**
     * Cache en mémoire concurrent : machineId -> Map<SensorType, MachineThreshold>
     * Évite de requêter MongoDB à chaque mesure reçue par MQTT.
     */
    private final ConcurrentHashMap<String, Map<SensorType, MachineThreshold>> cache = new ConcurrentHashMap<>();

    public MachineThresholdService(MachineThresholdRepository thresholdRepository,
                                   MachineRepository machineRepository) {
        this.thresholdRepository = thresholdRepository;
        this.machineRepository = machineRepository;
    }

    /**
     * Récupère tous les seuils configurés pour une machine.
     */
    public List<MachineThreshold> getThresholdsByMachine(String machineIdOrCode) {
        Machine machine = resolveMachine(machineIdOrCode);
        return thresholdRepository.findByMachineId(machine.getId());
    }

    /**
     * Récupère le seuil configuré pour une machine et un type de capteur.
     */
    public MachineThreshold getThreshold(String machineIdOrCode, SensorType sensorType) {
        Machine machine = resolveMachine(machineIdOrCode);
        return getThresholdByMachineAndType(machine, sensorType);
    }

    /**
     * Sauvegarde ou met à jour la configuration d'un seuil pour une machine.
     * Invalide immédiatement le cache mémoire pour prise en compte à chaud.
     */
    public MachineThreshold saveOrUpdateThreshold(String machineIdOrCode, MachineThreshold request) {
        Machine machine = resolveMachine(machineIdOrCode);

        if (request.getSensorType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR: Sensor type is required");
        }

        validateThresholdValues(request);

        Optional<MachineThreshold> existingOpt = thresholdRepository.findByMachineIdAndSensorType(
                machine.getId(), request.getSensorType()
        );

        MachineThreshold toSave;
        if (existingOpt.isPresent()) {
            toSave = existingOpt.get();
            toSave.setWarningMin(request.getWarningMin());
            toSave.setWarningMax(request.getWarningMax());
            toSave.setCriticalMin(request.getCriticalMin());
            toSave.setCriticalMax(request.getCriticalMax());
            toSave.setActive(request.isActive());
            toSave.setDescription(request.getDescription());
            if (StringUtils.hasText(request.getUnit())) {
                toSave.setUnit(request.getUnit());
            }
            toSave.setUpdatedAt(Instant.now());
        } else {
            toSave = new MachineThreshold(
                    machine.getId(),
                    machine.getCode(),
                    request.getSensorType(),
                    request.getWarningMin(),
                    request.getWarningMax(),
                    request.getCriticalMin(),
                    request.getCriticalMax(),
                    request.getUnit(),
                    request.isActive(),
                    request.getDescription()
            );
            toSave.setCreatedAt(Instant.now());
            toSave.setUpdatedAt(Instant.now());
        }

        MachineThreshold saved = thresholdRepository.save(toSave);
        invalidateCache(machine.getId(), machine.getCode());
        log.info("Updated threshold for machine [{}] sensor [{}]: warning=[{}-{}], critical=[{}-{}]",
                machine.getCode(), saved.getSensorType(),
                saved.getWarningMin(), saved.getWarningMax(),
                saved.getCriticalMin(), saved.getCriticalMax());

        return saved;
    }

    /**
     * Supprime un seuil de surveillance pour une machine et un capteur.
     */
    public void deleteThreshold(String machineIdOrCode, SensorType sensorType) {
        Machine machine = resolveMachine(machineIdOrCode);
        thresholdRepository.deleteByMachineIdAndSensorType(machine.getId(), sensorType);
        invalidateCache(machine.getId(), machine.getCode());
        log.info("Deleted threshold for machine [{}] sensor [{}]", machine.getCode(), sensorType);
    }

    /**
     * Évalue une mesure reçue pour une machine et un type de capteur.
     * Utilise le cache en mémoire pour une latence minimale.
     */
    public MachineThreshold.EvaluationResult evaluateReading(Machine machine, SensorType sensorType, double value) {
        MachineThreshold threshold = getThresholdCached(machine, sensorType);
        if (threshold == null) {
            return MachineThreshold.EvaluationResult.safe();
        }
        return threshold.evaluate(value);
    }

    /**
     * Récupère un seuil depuis le cache en mémoire ou charge depuis la base MongoDB.
     */
    public MachineThreshold getThresholdCached(Machine machine, SensorType sensorType) {
        if (machine == null || sensorType == null) {
            return null;
        }

        Map<SensorType, MachineThreshold> machineMap = cache.computeIfAbsent(machine.getId(), id -> {
            List<MachineThreshold> list = thresholdRepository.findByMachineId(id);
            Map<SensorType, MachineThreshold> map = new EnumMap<>(SensorType.class);
            for (MachineThreshold mt : list) {
                map.put(mt.getSensorType(), mt);
            }
            return map;
        });

        MachineThreshold mt = machineMap.get(sensorType);
        if (mt == null) {
            // Tentative de chargement ciblé
            Optional<MachineThreshold> opt = thresholdRepository.findByMachineIdAndSensorType(machine.getId(), sensorType);
            if (opt.isPresent()) {
                mt = opt.get();
            } else {
                // Seuil de surveillance par défaut
                mt = createDefaultThresholdForType(machine, sensorType);
            }
            machineMap.put(sensorType, mt);
        }
        return mt;
    }

    /**
     * Initialise les seuils par défaut pour une machine s'ils n'existent pas encore.
     * Garantit que chaque machine dispose de seuils explicites en base de données.
     */
    public void initializeDefaultThresholdsForMachine(Machine machine) {
        if (machine == null || !StringUtils.hasText(machine.getId())) {
            return;
        }

        for (SensorType type : SensorType.values()) {
            if (!thresholdRepository.existsByMachineIdAndSensorType(machine.getId(), type)) {
                MachineThreshold mt = createDefaultThresholdForType(machine, type);
                thresholdRepository.save(mt);
                log.info("Initialized default DB threshold for machine [{}] type [{}]", machine.getCode(), type);
            }
        }
        invalidateCache(machine.getId(), machine.getCode());
    }

    public MachineThreshold createDefaultThresholdForType(Machine machine, SensorType type) {
        switch (type) {
            case TEMPERATURE:
                return new MachineThreshold(
                        machine.getId(), machine.getCode(), type,
                        null, 60.0,
                        null, 80.0,
                        "°C", true, "Seuils par défaut de température"
                );
            case VIBRATION:
                return new MachineThreshold(
                        machine.getId(), machine.getCode(), type,
                        null, 4.5,
                        null, 8.0,
                        "mm/s", true, "Seuils par défaut de vibration"
                );
            case CURRENT:
                return new MachineThreshold(
                        machine.getId(), machine.getCode(), type,
                        null, 9.5,
                        null, 13.0,
                        "A", true, "Seuils par défaut de courant"
                );
            case RPM:
                return new MachineThreshold(
                        machine.getId(), machine.getCode(), type,
                        1000.0, 1800.0,
                        500.0, 2000.0,
                        "RPM", true, "Seuils par défaut de vitesse de rotation"
                );
            default:
                return new MachineThreshold(
                        machine.getId(), machine.getCode(), type,
                        null, null,
                        null, null,
                        type.getDefaultUnit(), true, "Seuil non configuré"
                );
        }
    }

    /**
     * Invalide l'entrée dans le cache pour cette machine.
     */
    public void invalidateCache(String machineId, String machineCode) {
        if (StringUtils.hasText(machineId)) {
            cache.remove(machineId);
        }
        if (StringUtils.hasText(machineCode)) {
            cache.remove(machineCode);
        }
    }

    /**
     * Vide l'intégralité du cache (utilitaire).
     */
    public void clearCache() {
        cache.clear();
    }

    private MachineThreshold getThresholdByMachineAndType(Machine machine, SensorType sensorType) {
        return thresholdRepository.findByMachineIdAndSensorType(machine.getId(), sensorType)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "THRESHOLD_NOT_FOUND: No threshold configured for machine " + machine.getCode() + " and sensor type " + sensorType
                ));
    }

    private Machine resolveMachine(String identifier) {
        if (!StringUtils.hasText(identifier)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR: Machine identifier is required");
        }
        return machineRepository.findByCode(identifier.trim())
                .or(() -> machineRepository.findById(identifier.trim()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "MACHINE_NOT_FOUND: Machine not found with id or code: " + identifier
                ));
    }

    /**
     * Valide la cohérence des valeurs de seuils :
     * - warningMin <= warningMax
     * - criticalMin <= criticalMax
     * - criticalMin <= warningMin <= warningMax <= criticalMax
     */
    private void validateThresholdValues(MachineThreshold t) {
        Double wMin = t.getWarningMin();
        Double wMax = t.getWarningMax();
        Double cMin = t.getCriticalMin();
        Double cMax = t.getCriticalMax();

        if (wMin != null && (Double.isNaN(wMin) || Double.isInfinite(wMin))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR: warningMin must be a finite number");
        }
        if (wMax != null && (Double.isNaN(wMax) || Double.isInfinite(wMax))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR: warningMax must be a finite number");
        }
        if (cMin != null && (Double.isNaN(cMin) || Double.isInfinite(cMin))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR: criticalMin must be a finite number");
        }
        if (cMax != null && (Double.isNaN(cMax) || Double.isInfinite(cMax))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR: criticalMax must be a finite number");
        }

        // Cohérence min/max pour un même niveau
        if (wMin != null && wMax != null && wMin > wMax) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR: warningMin cannot be greater than warningMax");
        }
        if (cMin != null && cMax != null && cMin > cMax) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR: criticalMin cannot be greater than criticalMax");
        }

        // Cohérence inter-niveaux pour les seuils inférieurs (criticalMin <= warningMin)
        if (cMin != null && wMin != null && cMin > wMin) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR: criticalMin cannot be greater than warningMin");
        }

        // Cohérence inter-niveaux pour les seuils supérieurs (warningMax <= criticalMax)
        if (wMax != null && cMax != null && wMax > cMax) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR: warningMax cannot be greater than criticalMax");
        }

        // Validation de l'unité si spécifiée
        if (StringUtils.hasText(t.getUnit()) && t.getSensorType() != null) {
            if (!t.getSensorType().isValidUnit(t.getUnit())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "VALIDATION_ERROR: Invalid unit [" + t.getUnit() + "] for sensor type " + t.getSensorType());
            }
        }
    }
}
