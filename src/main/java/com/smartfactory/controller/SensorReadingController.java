package com.smartfactory.controller;

import com.smartfactory.entity.Machine;
import com.smartfactory.entity.MachineStatus;
import com.smartfactory.entity.SensorType;
import com.smartfactory.service.SensorReadingService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Contrôleur REST pour la télémétrie temps réel et l'historique des mesures (Sprint 4 - US20 à US28).
 */
@RestController
@RequestMapping("/api/machines")
public class SensorReadingController {

    private final SensorReadingService readingService;

    public SensorReadingController(SensorReadingService readingService) {
        this.readingService = readingService;
    }

    /**
     * US20: Température en temps réel
     * GET /api/machines/{id}/sensors/temperature/current
     */
    @GetMapping("/{id}/sensors/temperature/current")
    @PreAuthorize("hasAnyAuthority('READINGS_READ', 'SENSOR_READ', 'ROLE_ADMIN')")
    public ResponseEntity<Map<String, Object>> getCurrentTemperature(@PathVariable String id) {
        Map<String, Object> response = readingService.getCurrentSensorReading(id, SensorType.TEMPERATURE);
        return ResponseEntity.ok(response);
    }

    /**
     * US21: Vibrations en temps réel
     * GET /api/machines/{id}/sensors/vibration/current
     */
    @GetMapping("/{id}/sensors/vibration/current")
    @PreAuthorize("hasAnyAuthority('READINGS_READ', 'SENSOR_READ', 'ROLE_ADMIN')")
    public ResponseEntity<Map<String, Object>> getCurrentVibration(@PathVariable String id) {
        Map<String, Object> response = readingService.getCurrentSensorReading(id, SensorType.VIBRATION);
        return ResponseEntity.ok(response);
    }

    /**
     * US22: Courant électrique en temps réel
     * GET /api/machines/{id}/sensors/current/current
     */
    @GetMapping("/{id}/sensors/current/current")
    @PreAuthorize("hasAnyAuthority('READINGS_READ', 'SENSOR_READ', 'ROLE_ADMIN')")
    public ResponseEntity<Map<String, Object>> getCurrentCurrent(@PathVariable String id) {
        Map<String, Object> response = readingService.getCurrentSensorReading(id, SensorType.CURRENT);
        return ResponseEntity.ok(response);
    }

    /**
     * US23: RPM en temps réel
     * GET /api/machines/{id}/sensors/rpm/current
     */
    @GetMapping("/{id}/sensors/rpm/current")
    @PreAuthorize("hasAnyAuthority('READINGS_READ', 'SENSOR_READ', 'ROLE_ADMIN')")
    public ResponseEntity<Map<String, Object>> getCurrentRpm(@PathVariable String id) {
        Map<String, Object> response = readingService.getCurrentSensorReading(id, SensorType.RPM);
        return ResponseEntity.ok(response);
    }

    /**
     * US24: État de la machine (RUNNING, IDLE, MAINTENANCE, FAILURE, OFFLINE)
     * GET /api/machines/{id}/status
     */
    @GetMapping("/{id}/status")
    @PreAuthorize("hasAnyAuthority('MACHINE_READ', 'ROLE_ADMIN')")
    public ResponseEntity<Map<String, Object>> getMachineStatus(@PathVariable String id) {
        Map<String, Object> response = readingService.getMachineStatusInfo(id);
        return ResponseEntity.ok(response);
    }

    /**
     * US24: Mise à jour manuelle de l'état de la machine (ex: passage en MAINTENANCE)
     * PUT /api/machines/{id}/status
     */
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyAuthority('MACHINE_WRITE', 'MACHINE_UPDATE', 'ROLE_ADMIN')")
    public ResponseEntity<Machine> updateMachineStatus(
            @PathVariable String id,
            @RequestBody Map<String, String> body
    ) {
        String statusStr = body.get("status");
        if (!StringUtils.hasText(statusStr)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR: Le statut est requis");
        }
        try {
            MachineStatus status = MachineStatus.valueOf(statusStr.trim().toUpperCase());
            Machine updated = readingService.updateMachineStatus(id, status);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR: Statut invalide: " + statusStr);
        }
    }

    /**
     * US25: Résumé de la dernière télémétrie complète (reconnexion client WebSocket)
     * GET /api/machines/{id}/readings/latest
     */
    @GetMapping("/{id}/readings/latest")
    @PreAuthorize("hasAnyAuthority('READINGS_READ', 'SENSOR_READ', 'ROLE_ADMIN')")
    public ResponseEntity<Map<String, Object>> getLatestReadings(@PathVariable String id) {
        Map<String, Object> response = readingService.getLatestTelemetrySummary(id);
        return ResponseEntity.ok(response);
    }

    /**
     * US26: Consultation de l'historique paginé dans la base dédiée SmartFactoryHistory
     * GET /api/machines/{id}/readings?from=...&to=...&sensorType=...&page=0&size=20&sort=timestamp,desc
     */
    @GetMapping("/{id}/readings")
    @PreAuthorize("hasAnyAuthority('READINGS_READ', 'SENSOR_HISTORY_READ', 'ROLE_ADMIN')")
    public ResponseEntity<Map<String, Object>> getMachineReadings(
            @PathVariable String id,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String sensorType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "timestamp,desc") String sort) {

        Instant fromInstant = parseInstant(from, "from");
        Instant toInstant = parseInstant(to, "to");

        SensorType parsedType = null;
        if (StringUtils.hasText(sensorType)) {
            parsedType = SensorType.fromString(sensorType);
            if (parsedType == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR: Invalid sensorType: " + sensorType);
            }
        }

        int boundedSize = Math.max(1, Math.min(size, 100));
        String[] sortParts = sort.split(",");
        Sort.Direction direction = sortParts.length > 1 && "asc".equalsIgnoreCase(sortParts[1])
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        String sortProperty = sortParts[0].trim();
        Sort sortObj = Sort.by(direction, sortProperty);

        Pageable pageable = PageRequest.of(page, boundedSize, sortObj);
        Map<String, Object> response = readingService.getReadings(id, fromInstant, toInstant, parsedType, pageable);
        return ResponseEntity.ok(response);
    }

    /**
     * US27 & US28: Séries temporelles pour graphiques avec filtrage par période (1h, 24h, 7d, 30d)
     * GET /api/machines/{id}/readings/timeseries
     */
    @GetMapping({"/{id}/readings/timeseries", "/{id}/charts"})
    @PreAuthorize("hasAnyAuthority('READINGS_READ', 'SENSOR_HISTORY_READ', 'ROLE_ADMIN')")
    public ResponseEntity<Map<String, Object>> getTimeseries(
            @PathVariable String id,
            @RequestParam(required = false) String sensorType,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "avg") String aggregate,
            @RequestParam(defaultValue = "200") int maxPoints) {

        Instant fromInstant = parseInstant(from, "from");
        Instant toInstant = parseInstant(to, "to");

        SensorType parsedType = null;
        if (StringUtils.hasText(sensorType)) {
            parsedType = SensorType.fromString(sensorType);
            if (parsedType == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR: Invalid sensorType: " + sensorType);
            }
        }

        Map<String, Object> response = readingService.getTimeseries(
                id, parsedType, period, fromInstant, toInstant, aggregate, maxPoints
        );
        return ResponseEntity.ok(response);
    }

    private Instant parseInstant(String str, String fieldName) {
        if (!StringUtils.hasText(str)) {
            return null;
        }
        try {
            return Instant.parse(str.trim());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR: Invalid '" + fieldName + "' date format: " + str);
        }
    }
}
