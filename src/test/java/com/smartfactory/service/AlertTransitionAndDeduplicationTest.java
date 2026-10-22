package com.smartfactory.service;

import com.smartfactory.entity.*;
import com.smartfactory.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AlertTransitionAndDeduplicationTest {

    @Autowired
    private SensorReadingService sensorReadingService;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private MachineRepository machineRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private MachineThresholdRepository thresholdRepository;

    @Autowired
    private AnomalyRepository anomalyRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    private Machine machineA;
    private Machine machineB;
    private Sensor sensorTempA;
    private Sensor sensorVibA;
    private Sensor sensorTempB;

    @BeforeEach
    void setUp() {
        alertRepository.deleteAll();
        anomalyRepository.deleteAll();
        notificationRepository.deleteAll();
        thresholdRepository.deleteAll();
        sensorRepository.deleteAll();
        machineRepository.deleteAll();

        // Machine A
        machineA = machineRepository.save(new Machine("Fraiseuse A", "MACH-A", "Fraiseuse", "zone-1", MachineStatus.RUNNING, "Desc A", List.of()));
        sensorTempA = sensorRepository.save(new Sensor(machineA.getId(), SensorType.TEMPERATURE, "°C"));
        sensorVibA = sensorRepository.save(new Sensor(machineA.getId(), SensorType.VIBRATION, "mm/s"));

        // Threshold Machine A Temperature: WARNING >= 60.0, CRITICAL >= 80.0
        MachineThreshold threshTempA = new MachineThreshold();
        threshTempA.setMachineId(machineA.getId());
        threshTempA.setMachineCode(machineA.getCode());
        threshTempA.setSensorType(SensorType.TEMPERATURE);
        threshTempA.setUnit("°C");
        threshTempA.setWarningMax(60.0);
        threshTempA.setCriticalMax(80.0);
        threshTempA.setActive(true);
        thresholdRepository.save(threshTempA);

        // Threshold Machine A Vibration: WARNING >= 5.0, CRITICAL >= 10.0
        MachineThreshold threshVibA = new MachineThreshold();
        threshVibA.setMachineId(machineA.getId());
        threshVibA.setMachineCode(machineA.getCode());
        threshVibA.setSensorType(SensorType.VIBRATION);
        threshVibA.setUnit("mm/s");
        threshVibA.setWarningMax(5.0);
        threshVibA.setCriticalMax(10.0);
        threshVibA.setActive(true);
        thresholdRepository.save(threshVibA);

        // Machine B
        machineB = machineRepository.save(new Machine("Tour B", "MACH-B", "Tour", "zone-1", MachineStatus.RUNNING, "Desc B", List.of()));
        sensorTempB = sensorRepository.save(new Sensor(machineB.getId(), SensorType.TEMPERATURE, "°C"));

        MachineThreshold threshTempB = new MachineThreshold();
        threshTempB.setMachineId(machineB.getId());
        threshTempB.setMachineCode(machineB.getCode());
        threshTempB.setSensorType(SensorType.TEMPERATURE);
        threshTempB.setUnit("°C");
        threshTempB.setWarningMax(70.0);
        threshTempB.setCriticalMax(90.0);
        threshTempB.setActive(true);
        thresholdRepository.save(threshTempB);
    }

    private String buildPayload(String machineCode, String sensorType, double value, String unit) {
        return String.format("""
                {
                  "machineId": "%s",
                  "sensorType": "%s",
                  "value": %.1f,
                  "unit": "%s",
                  "timestamp": "%s"
                }
                """, machineCode, sensorType, value, unit, Instant.now().toString());
    }

    @Test
    @DisplayName("Section 10.1 & 10.2: Transitions NORMAL -> WARNING -> CRITICAL -> NORMAL -> Nouvel épisode WARNING")
    void testAlertLifecycleTransitionsAndDeduplication() {
        String topicA = "factory/machines/MACH-A/temperature";

        // 1. NORMAL -> WARNING (65.0°C)
        sensorReadingService.validateAndProcess(topicA, buildPayload("MACH-A", "TEMPERATURE", 65.0, "°C"));

        List<Alert> activeAlerts = alertRepository.findByMachineIdAndSensorTypeAndStatus(machineA.getId(), SensorType.TEMPERATURE, "ACTIVE");
        assertThat(activeAlerts).hasSize(1);
        Alert warningAlert = activeAlerts.get(0);
        assertThat(warningAlert.getSeverity()).isEqualTo("WARNING");
        assertThat(warningAlert.getValue()).isEqualTo(65.0);

        // 2. Déduplication : 10 lectures consécutives en WARNING (66.0°C)
        for (int i = 0; i < 10; i++) {
            sensorReadingService.validateAndProcess(topicA, buildPayload("MACH-A", "TEMPERATURE", 66.0 + i * 0.1, "°C"));
        }
        activeAlerts = alertRepository.findByMachineIdAndSensorTypeAndStatus(machineA.getId(), SensorType.TEMPERATURE, "ACTIVE");
        assertThat(activeAlerts).hasSize(1);
        assertThat(activeAlerts.get(0).getSeverity()).isEqualTo("WARNING");
        assertThat(alertRepository.findAll()).hasSize(1); // Pas d'alertes fantômes

        // 3. WARNING -> CRITICAL (85.0°C)
        sensorReadingService.validateAndProcess(topicA, buildPayload("MACH-A", "TEMPERATURE", 85.0, "°C"));

        activeAlerts = alertRepository.findByMachineIdAndSensorTypeAndStatus(machineA.getId(), SensorType.TEMPERATURE, "ACTIVE");
        assertThat(activeAlerts).hasSize(1);
        Alert criticalAlert = activeAlerts.get(0);
        assertThat(criticalAlert.getId()).isEqualTo(warningAlert.getId()); // Même alerte mise à jour en place
        assertThat(criticalAlert.getSeverity()).isEqualTo("CRITICAL");
        assertThat(criticalAlert.getValue()).isEqualTo(85.0);
        assertThat(alertRepository.findAll()).hasSize(1); // Exactement 1 alerte au total, 0 résiduel en WARNING

        // 4. Déduplication : 10 lectures consécutives en CRITICAL (88.0°C)
        for (int i = 0; i < 10; i++) {
            sensorReadingService.validateAndProcess(topicA, buildPayload("MACH-A", "TEMPERATURE", 88.0 + i * 0.2, "°C"));
        }
        activeAlerts = alertRepository.findByMachineIdAndSensorTypeAndStatus(machineA.getId(), SensorType.TEMPERATURE, "ACTIVE");
        assertThat(activeAlerts).hasSize(1);
        assertThat(activeAlerts.get(0).getSeverity()).isEqualTo("CRITICAL");
        assertThat(alertRepository.findAll()).hasSize(1);

        // 5. CRITICAL -> NORMAL (50.0°C)
        sensorReadingService.validateAndProcess(topicA, buildPayload("MACH-A", "TEMPERATURE", 50.0, "°C"));

        activeAlerts = alertRepository.findByMachineIdAndSensorTypeAndStatus(machineA.getId(), SensorType.TEMPERATURE, "ACTIVE");
        assertThat(activeAlerts).isEmpty(); // Résolue automatiquement

        Alert resolvedAlert = alertRepository.findById(warningAlert.getId()).orElseThrow();
        assertThat(resolvedAlert.getStatus()).isEqualTo("RESOLVED");
        assertThat(resolvedAlert.getResolvedAt()).isNotNull();

        // 6. NORMAL -> WARNING : Nouvel épisode propre (68.0°C)
        sensorReadingService.validateAndProcess(topicA, buildPayload("MACH-A", "TEMPERATURE", 68.0, "°C"));

        activeAlerts = alertRepository.findByMachineIdAndSensorTypeAndStatus(machineA.getId(), SensorType.TEMPERATURE, "ACTIVE");
        assertThat(activeAlerts).hasSize(1);
        Alert newEpisodeAlert = activeAlerts.get(0);
        assertThat(newEpisodeAlert.getId()).isNotEqualTo(warningAlert.getId()); // Nouvel identifiant propre
        assertThat(newEpisodeAlert.getStatus()).isEqualTo("ACTIVE");
        assertThat(newEpisodeAlert.getSeverity()).isEqualTo("WARNING");
        assertThat(newEpisodeAlert.getValue()).isEqualTo(68.0);
    }

    @Test
    @DisplayName("Section 10.3: Indépendance stricte machine / capteur")
    void testMachineAndSensorIndependence() {
        String topicTempA = "factory/machines/MACH-A/temperature";
        String topicVibA = "factory/machines/MACH-A/vibration";
        String topicTempB = "factory/machines/MACH-B/temperature";

        // Dépassement sur Machine A Température (75.0°C -> WARNING)
        sensorReadingService.validateAndProcess(topicTempA, buildPayload("MACH-A", "TEMPERATURE", 75.0, "°C"));

        // Dépassement sur Machine A Vibration (12.0 mm/s -> CRITICAL)
        sensorReadingService.validateAndProcess(topicVibA, buildPayload("MACH-A", "VIBRATION", 12.0, "mm/s"));

        // Machine B Température reste normale (55.0°C < 70.0)
        sensorReadingService.validateAndProcess(topicTempB, buildPayload("MACH-B", "TEMPERATURE", 55.0, "°C"));

        List<Alert> alertsMachATemp = alertRepository.findByMachineIdAndSensorTypeAndStatus(machineA.getId(), SensorType.TEMPERATURE, "ACTIVE");
        assertThat(alertsMachATemp).hasSize(1);
        assertThat(alertsMachATemp.get(0).getSeverity()).isEqualTo("WARNING");

        List<Alert> alertsMachAVib = alertRepository.findByMachineIdAndSensorTypeAndStatus(machineA.getId(), SensorType.VIBRATION, "ACTIVE");
        assertThat(alertsMachAVib).hasSize(1);
        assertThat(alertsMachAVib.get(0).getSeverity()).isEqualTo("CRITICAL");

        List<Alert> alertsMachBTemp = alertRepository.findByMachineIdAndSensorTypeAndStatus(machineB.getId(), SensorType.TEMPERATURE, "ACTIVE");
        assertThat(alertsMachBTemp).isEmpty();
    }
}
