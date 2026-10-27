package com.smartfactory.service;

import com.smartfactory.entity.*;
import com.smartfactory.repository.*;
import com.smartfactory.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MaintenanceInterventionServiceTest {

    private MaintenanceInterventionRepository interventionRepository;
    private MachineRepository machineRepository;
    private UserRepository userRepository;
    private AlertRepository alertRepository;
    private AnomalyRepository anomalyRepository;
    private AlertService alertService;
    private NotificationService notificationService;
    private WeeklyHistoryDatabaseService weeklyHistoryDatabaseService;
    private SensorService sensorService;
    private MongoTemplate mongoTemplate;
    private MultiSensorAnalysisService multiSensorAnalysisService;

    private MaintenanceInterventionService interventionService;
    private MaintenanceRecommendationService recommendationService;

    private Machine testMachine;
    private User testTechnician;

    @BeforeEach
    void setUp() {
        interventionRepository = Mockito.mock(MaintenanceInterventionRepository.class);
        machineRepository = Mockito.mock(MachineRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        alertRepository = Mockito.mock(AlertRepository.class);
        anomalyRepository = Mockito.mock(AnomalyRepository.class);
        alertService = Mockito.mock(AlertService.class);
        notificationService = Mockito.mock(NotificationService.class);
        weeklyHistoryDatabaseService = Mockito.mock(WeeklyHistoryDatabaseService.class);
        sensorService = Mockito.mock(SensorService.class);
        mongoTemplate = Mockito.mock(MongoTemplate.class);
        multiSensorAnalysisService = Mockito.mock(MultiSensorAnalysisService.class);

        interventionService = new MaintenanceInterventionService(
                interventionRepository, machineRepository, userRepository,
                alertRepository, anomalyRepository, alertService, notificationService,
                weeklyHistoryDatabaseService, sensorService, mongoTemplate
        );

        AnomalyService mockAnomalyService = Mockito.mock(AnomalyService.class);
        recommendationService = new MaintenanceRecommendationService(
                machineRepository, alertRepository, mockAnomalyService,
                multiSensorAnalysisService, interventionRepository, interventionService
        );

        testMachine = new Machine("Fraiseuse CNC", "CNC-024", "Fraiseuse", "zone-1", MachineStatus.RUNNING, "Desc", List.of());
        testMachine.setId("mach-1");

        testTechnician = new User("Pierre", "Martin", "tech@factory.com", "pass", Role.TECHNICIAN, "ACTIVE");
        testTechnician.setId("user-tech-1");

        when(machineRepository.findByCode("CNC-024")).thenReturn(Optional.of(testMachine));
        when(machineRepository.findById("mach-1")).thenReturn(Optional.of(testMachine));
        when(userRepository.findById("user-tech-1")).thenReturn(Optional.of(testTechnician));
        when(interventionRepository.save(any(MaintenanceIntervention.class))).thenAnswer(inv -> {
            MaintenanceIntervention i = inv.getArgument(0);
            if (i.getId() == null) i.setId("interv-1");
            return i;
        });
    }

    @Test
    @DisplayName("US42 - Rejects PREVENTIVE maintenance if preventiveSubtype is missing")
    void testPreventiveRequiresSubtype() {
        MaintenanceIntervention req = new MaintenanceIntervention();
        req.setMachineId("CNC-024");
        req.setTitle("Maintenance préventive semestrielle");
        req.setType(MaintenanceType.PREVENTIVE);
        req.setPreventiveSubtype(null); // MANQUANT

        assertThatThrownBy(() -> interventionService.createIntervention(req, "admin@factory.com"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("OBLIGATOIRE");
    }

    @Test
    @DisplayName("US42 - Rejects CORRECTIVE maintenance if preventiveSubtype is provided")
    void testCorrectiveRejectsPreventiveSubtype() {
        MaintenanceIntervention req = new MaintenanceIntervention();
        req.setMachineId("CNC-024");
        req.setTitle("Remplacement courroie rompue");
        req.setType(MaintenanceType.CORRECTIVE);
        req.setPreventiveSubtype(PreventiveSubtype.SYSTEMATIC); // INTERDIT SUR CORRECTIVE

        assertThatThrownBy(() -> interventionService.createIntervention(req, "admin@factory.com"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("n'est autorisé que pour une maintenance PREVENTIVE");
    }

    @Test
    @DisplayName("US42 & US43 - Valid PREVENTIVE SYSTEMATIC intervention created and assigned to technician")
    void testValidInterventionCreationAndAssignment() {
        MaintenanceIntervention req = new MaintenanceIntervention();
        req.setMachineId("CNC-024");
        req.setTitle("Graissage périodique 500h");
        req.setType(MaintenanceType.PREVENTIVE);
        req.setPreventiveSubtype(PreventiveSubtype.SYSTEMATIC);
        req.setTechnicianId("user-tech-1");

        MaintenanceIntervention created = interventionService.createIntervention(req, "admin@factory.com");
        assertThat(created.getId()).isEqualTo("interv-1");
        assertThat(created.getType()).isEqualTo(MaintenanceType.PREVENTIVE);
        assertThat(created.getPreventiveSubtype()).isEqualTo(PreventiveSubtype.SYSTEMATIC);
        assertThat(created.getTechnicianId()).isEqualTo("user-tech-1");
        assertThat(created.getStatus()).isEqualTo(InterventionStatus.ASSIGNED);
    }

    @Test
    @DisplayName("US45 & US46 & US47 & US48 - Full intervention workflow: Diagnosis, Action, Observation, and Close")
    void testInterventionFullWorkflow() {
        MaintenanceIntervention interv = new MaintenanceIntervention(
                "mach-1", "CNC-024", "Panne broche", "Description",
                MaintenanceType.CORRECTIVE, null, InterventionPriority.HIGH
        );
        interv.setId("interv-1");
        interv.setAlertId("alt-1");
        when(interventionRepository.findById("interv-1")).thenReturn(Optional.of(interv));

        // 1. Ajouter un diagnostic (US45)
        MaintenanceDiagnosis diagReq = new MaintenanceDiagnosis("Rupture de roulement", "Jeu axial important constaté", "user-tech-1", "Pierre Martin");
        interventionService.addDiagnosis("interv-1", diagReq, "user-tech-1", "Pierre Martin");
        assertThat(interv.getDiagnoses()).hasSize(1);
        assertThat(interv.getStatus()).isEqualTo(InterventionStatus.IN_PROGRESS);

        // 2. Ajouter une action (US46)
        MaintenanceAction actReq = new MaintenanceAction("Remplacement roulement 6205-2RS", "user-tech-1", "Pierre Martin", "Roulement 6205", 45);
        interventionService.addAction("interv-1", actReq, "user-tech-1", "Pierre Martin");
        assertThat(interv.getActions()).hasSize(1);

        // 3. Ajouter une observation (US47)
        MaintenanceObservation obsReq = new MaintenanceObservation("Vérifier à nouveau la température dans 24h", "user-tech-1", "Pierre Martin", "OPERATING_CONDITION");
        interventionService.addObservation("interv-1", obsReq, "user-tech-1", "Pierre Martin");
        assertThat(interv.getObservations()).hasSize(1);

        // 4. Clôturer l'intervention (US48)
        MaintenanceIntervention closeReq = new MaintenanceIntervention();
        closeReq.setClosureNotes("Broche réparée et testée à vide");
        closeReq.setRestoreMachineStatus(true);
        MaintenanceIntervention closed = interventionService.closeIntervention("interv-1", closeReq, "Pierre Martin");

        assertThat(closed.getStatus()).isEqualTo(InterventionStatus.COMPLETED);
        assertThat(closed.getClosedAt()).isNotNull();
        verify(alertService, times(1)).resolveAlert(eq("alt-1"), eq("Pierre Martin"), any());
    }

    @Test
    @DisplayName("US50 - Recommendations suggest CORRECTIVE when critical alert exists")
    void testRecommendationOnCriticalAlert() {
        when(alertRepository.findByMachineId("mach-1")).thenReturn(List.of(
                new Alert("mach-1", "s-1", SensorType.VIBRATION, "CRITICAL", 5.2, 4.5, "Vibration critique", Instant.now())
        ));
        when(multiSensorAnalysisService.analyzeMachine(any())).thenReturn(
                new MultiSensorAnalysisService.MultiSensorAnalysisResult("mach-1", "CNC-024", false, "NORMAL", "NORMAL", "Nominal", null, 0L)
        );

        List<MaintenanceRecommendationService.Recommendation> recs = recommendationService.generateRecommendations("CNC-024");
        assertThat(recs).isNotEmpty();
        assertThat(recs.get(0).getRecommendedType()).isEqualTo(MaintenanceType.CORRECTIVE);
        assertThat(recs.get(0).getPriority()).isEqualTo(InterventionPriority.CRITICAL);
    }
}
