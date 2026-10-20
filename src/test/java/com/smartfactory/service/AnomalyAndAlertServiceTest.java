package com.smartfactory.service;

import com.smartfactory.entity.*;
import com.smartfactory.repository.AlertRepository;
import com.smartfactory.repository.AnomalyRepository;
import com.smartfactory.repository.MachineRepository;
import com.smartfactory.repository.SensorReadingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AnomalyAndAlertServiceTest {

    private AnomalyRepository anomalyRepository;
    private MachineRepository machineRepository;
    private AlertRepository alertRepository;
    private NotificationService notificationService;
    private RealtimeMessageService realtimeMessageService;
    private SensorReadingRepository readingRepository;
    private TelemetryCacheService telemetryCacheService;
    private MachineThresholdService thresholdService;

    private AnomalyService anomalyService;
    private AlertService alertService;
    private AdvancedAnomalyDetectionService advancedDetectionService;
    private MultiSensorAnalysisService multiSensorAnalysisService;
    private AnomalyScoreService anomalyScoreService;

    private Machine testMachine;
    private Sensor testSensor;

    @BeforeEach
    void setUp() {
        anomalyRepository = Mockito.mock(AnomalyRepository.class);
        machineRepository = Mockito.mock(MachineRepository.class);
        alertRepository = Mockito.mock(AlertRepository.class);
        notificationService = Mockito.mock(NotificationService.class);
        realtimeMessageService = Mockito.mock(RealtimeMessageService.class);
        readingRepository = Mockito.mock(SensorReadingRepository.class);
        telemetryCacheService = Mockito.mock(TelemetryCacheService.class);
        thresholdService = Mockito.mock(MachineThresholdService.class);

        anomalyService = new AnomalyService(anomalyRepository, machineRepository);
        alertService = new AlertService(alertRepository, anomalyRepository, machineRepository, notificationService, realtimeMessageService);
        advancedDetectionService = new AdvancedAnomalyDetectionService(readingRepository);
        multiSensorAnalysisService = new MultiSensorAnalysisService(telemetryCacheService);
        anomalyScoreService = new AnomalyScoreService(machineRepository, telemetryCacheService, alertRepository, multiSensorAnalysisService, thresholdService);

        testMachine = new Machine("Fraiseuse CNC", "CNC-024", "Fraiseuse", "zone-1", MachineStatus.RUNNING, "Desc", List.of());
        testMachine.setId("mach-1");

        testSensor = new Sensor("mach-1", SensorType.TEMPERATURE, "°C");
        testSensor.setId("sens-1");
    }

    @Test
    @DisplayName("US33 - Threshold breach creates Anomaly and subsequent breaches update existing incident without spamming")
    void testAnomalyCreationAndDeduplication() {
        MachineThreshold.EvaluationResult criticalEval = new MachineThreshold.EvaluationResult(
                true, "CRITICAL", 80.0, "Température critique 88.5 °C"
        );

        when(anomalyRepository.findFirstByMachineIdAndSensorTypeAndStatusIn(eq("mach-1"), eq(SensorType.TEMPERATURE), any()))
                .thenReturn(Optional.empty());
        when(anomalyRepository.save(any(Anomaly.class))).thenAnswer(inv -> inv.getArgument(0));

        // 1. Première mesure anormale -> création
        Optional<Anomaly> created = anomalyService.processReadingAnomaly(
                testMachine, testSensor, SensorType.TEMPERATURE, 88.5, criticalEval, Instant.now()
        );

        assertThat(created).isPresent();
        assertThat(created.get().getSeverity()).isEqualTo("CRITICAL");
        assertThat(created.get().getOccurrenceCount()).isEqualTo(1);

        // 2. Deuxième mesure anormale successive -> mise à jour de l'incident (déduplication)
        Anomaly existingAnomaly = created.get();
        when(anomalyRepository.findFirstByMachineIdAndSensorTypeAndStatusIn(eq("mach-1"), eq(SensorType.TEMPERATURE), any()))
                .thenReturn(Optional.of(existingAnomaly));

        Optional<Anomaly> updated = anomalyService.processReadingAnomaly(
                testMachine, testSensor, SensorType.TEMPERATURE, 91.2, criticalEval, Instant.now()
        );

        assertThat(updated).isPresent();
        assertThat(updated.get().getOccurrenceCount()).isEqualTo(2);
        assertThat(updated.get().getObservedValue()).isEqualTo(91.2);
    }

    @Test
    @DisplayName("US34 - Advanced detection returns INSUFFICIENT_DATA if fewer than 5 samples")
    void testAdvancedDetectionInsufficientData() {
        when(readingRepository.findByMachineIdAndSensorType(eq("mach-1"), eq(SensorType.TEMPERATURE), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        new SensorReading("mach-1", "CNC-024", "s-1", SensorType.TEMPERATURE, 55.0, "°C", Instant.now(), Instant.now())
                )));

        AdvancedAnomalyDetectionService.DetectionResult res = advancedDetectionService.analyzeSensorBehavior("mach-1", SensorType.TEMPERATURE, 55.0);
        assertThat(res.getStatus()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(res.isAnomalyDetected()).isFalse();
    }

    @Test
    @DisplayName("US35 - Multi-sensor analysis identifies motor stall (low RPM + high current)")
    void testMultiSensorMotorStall() {
        Instant now = Instant.now();
        when(telemetryCacheService.getLatestReading("mach-1", SensorType.TEMPERATURE))
                .thenReturn(Optional.of(new SensorReading("mach-1", "CNC-024", "s-1", SensorType.TEMPERATURE, 45.0, "°C", now, now)));
        when(telemetryCacheService.getLatestReading("mach-1", SensorType.VIBRATION))
                .thenReturn(Optional.of(new SensorReading("mach-1", "CNC-024", "s-2", SensorType.VIBRATION, 1.2, "mm/s", now, now)));
        when(telemetryCacheService.getLatestReading("mach-1", SensorType.CURRENT))
                .thenReturn(Optional.of(new SensorReading("mach-1", "CNC-024", "s-3", SensorType.CURRENT, 16.5, "A", now, now)));
        when(telemetryCacheService.getLatestReading("mach-1", SensorType.RPM))
                .thenReturn(Optional.of(new SensorReading("mach-1", "CNC-024", "s-4", SensorType.RPM, 15.0, "rpm", now, now)));

        var result = multiSensorAnalysisService.analyzeMachine(testMachine);
        assertThat(result.isMultiSensorAnomaly()).isTrue();
        assertThat(result.getDiagnosisPattern()).isEqualTo("MOTOR_STALL");
        assertThat(result.getSeverity()).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("US36 - Anomaly score returns NO_DATA when machine has no recent readings")
    void testAnomalyScoreNoData() {
        when(machineRepository.findByCode("CNC-024")).thenReturn(Optional.of(testMachine));
        when(telemetryCacheService.getLatestReading(eq("mach-1"), any())).thenReturn(Optional.empty());

        var scoreResult = anomalyScoreService.calculateScore("CNC-024");
        assertThat(scoreResult.getState()).isEqualTo("NO_DATA");
        assertThat(scoreResult.getScore()).isNull();
    }

    @Test
    @DisplayName("US37 & US40 & US41 - Alert creation dispatches notifications and supports formal acknowledgement")
    void testAlertCreationAndAcknowledgement() {
        Anomaly anomaly = new Anomaly("mach-1", "CNC-024", "sens-1", SensorType.TEMPERATURE, 88.5, 80.0, "CRITICAL_MAX", "CRITICAL", "Alerte test", Instant.now());
        anomaly.setId("anom-1");

        when(alertRepository.findFirstByMachineIdAndSensorTypeAndStatus("mach-1", SensorType.TEMPERATURE, "ACTIVE"))
                .thenReturn(Optional.empty());
        when(alertRepository.save(any(Alert.class))).thenAnswer(inv -> {
            Alert a = inv.getArgument(0);
            if (a.getId() == null) a.setId("alt-1");
            return a;
        });

        // 1. Création de l'alerte
        Alert createdAlert = alertService.createOrUpdateAlertForAnomaly(anomaly, testMachine, "CRITICAL", 88.5, 80.0, "Surtempérature");
        assertThat(createdAlert.getStatus()).isEqualTo("ACTIVE");
        assertThat(createdAlert.getSeverity()).isEqualTo("CRITICAL");
        verify(notificationService, times(1)).notifyAlert(any(Alert.class));
        verify(realtimeMessageService, times(1)).broadcastAlert(any(Alert.class), eq("CNC-024"));

        // 2. Acquittement (US41)
        when(alertRepository.findById("alt-1")).thenReturn(Optional.of(createdAlert));
        Alert ackAlert = alertService.acknowledgeAlert("alt-1", "tech@factory.com", "Pris en charge");
        assertThat(ackAlert.getStatus()).isEqualTo("ACKNOWLEDGED");
        assertThat(ackAlert.getAcknowledgedBy()).isEqualTo("tech@factory.com");
        assertThat(ackAlert.getAcknowledgedAt()).isNotNull();
    }
}
