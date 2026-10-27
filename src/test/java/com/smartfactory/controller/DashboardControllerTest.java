package com.smartfactory.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfactory.entity.*;
import com.smartfactory.repository.*;
import com.smartfactory.security.JwtService;
import com.smartfactory.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MachineRepository machineRepository;

    @Autowired
    private ZoneRepository zoneRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private AnomalyRepository anomalyRepository;

    @Autowired
    private MaintenanceInterventionRepository interventionRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    private User admin;
    private User respIndus;
    private User technician;
    private User operator;

    private String adminToken;
    private String respIndusToken;
    private String technicianToken;
    private String operatorToken;

    private Zone zone1;
    private Machine machine1;
    private Machine machine2;

    @BeforeEach
    void setUp() {
        interventionRepository.deleteAll();
        alertRepository.deleteAll();
        anomalyRepository.deleteAll();
        sensorRepository.deleteAll();
        machineRepository.deleteAll();
        zoneRepository.deleteAll();
        userRepository.deleteAll();

        // 1. Create zones
        zone1 = zoneRepository.save(new Zone("Zone Usinage A", "Atelier usinage haute précision", "Bâtiment Nord"));

        // 2. Create machines
        machine1 = new Machine("Fraiseuse CNC-01", "CNC-01", "Fraiseuse", zone1.getId(), MachineStatus.RUNNING, "Machine principale", List.of());
        machine1 = machineRepository.save(machine1);

        machine2 = new Machine("Tour Numérique TN-02", "TN-02", "Tour", zone1.getId(), MachineStatus.FAILURE, "Tour secondaire", List.of());
        machine2 = machineRepository.save(machine2);

        // 3. Create sensor for machine 1
        sensorRepository.save(new Sensor(machine1.getId(), SensorType.TEMPERATURE, "°C"));
        sensorRepository.save(new Sensor(machine1.getId(), SensorType.VIBRATION, "mm/s"));

        // 4. Create active alert & anomaly
        Alert alert = new Alert();
        alert.setMachineId(machine2.getId());
        alert.setMachineCode(machine2.getCode());
        alert.setSeverity("CRITICAL");
        alert.setMessage("Surchauffe critique détectée");
        alert.setSensorType(SensorType.TEMPERATURE);
        alert.setStatus("ACTIVE");
        alert.setTimestamp(Instant.now());
        alertRepository.save(alert);

        Anomaly anomaly = new Anomaly();
        anomaly.setMachineId(machine2.getId());
        anomaly.setMachineCode(machine2.getCode());
        anomaly.setSensorType(SensorType.TEMPERATURE);
        anomaly.setSeverity("CRITICAL");
        anomaly.setStatus("OPEN");
        anomaly.setObservedValue(98.5);
        anomaly.setThresholdValue(85.0);
        anomaly.setMessage("Température critique dépassée");
        anomaly.setTimestamp(Instant.now());
        anomalyRepository.save(anomaly);

        // 5. Create corrective intervention
        MaintenanceIntervention inter = new MaintenanceIntervention(
                machine2.getId(), machine2.getCode(), "Réparation variateur", "Dépannage d'urgence",
                MaintenanceType.CORRECTIVE, null, InterventionPriority.HIGH
        );
        inter.setStatus(InterventionStatus.COMPLETED);
        inter.setDurationMinutes(90);
        inter.setCreatedAt(Instant.now().minusSeconds(3600));
        inter.setCompletedAt(Instant.now());
        interventionRepository.save(inter);

        // 6. Create users
        admin = userRepository.save(new User("Admin", "Super", "admin@smartfactory.com", passwordEncoder.encode("pass123"), Role.ADMIN, "ACTIVE"));
        respIndus = userRepository.save(new User("Claire", "Resp", "resp@smartfactory.com", passwordEncoder.encode("pass123"), Role.RESPONSABLE_INDUSTRIEL, "ACTIVE"));
        technician = userRepository.save(new User("Jean", "Tech", "tech@smartfactory.com", passwordEncoder.encode("pass123"), Role.TECHNICIAN, "ACTIVE"));
        operator = userRepository.save(new User("Paul", "Oper", "oper@smartfactory.com", passwordEncoder.encode("pass123"), Role.OPERATOR, "ACTIVE"));

        adminToken = jwtService.generateToken(admin);
        respIndusToken = jwtService.generateToken(respIndus);
        technicianToken = jwtService.generateToken(technician);
        operatorToken = jwtService.generateToken(operator);
    }

    @Test
    @DisplayName("US51: GET /api/dashboard/overview - Accessible par RI et Admin")
    void testGetOverview() throws Exception {
        mockMvc.perform(get("/api/dashboard/overview")
                        .header("Authorization", "Bearer " + respIndusToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMachines", is(2)))
                .andExpect(jsonPath("$.machinesByStatus.RUNNING", is(1)))
                .andExpect(jsonPath("$.machinesByStatus.FAILURE", is(1)))
                .andExpect(jsonPath("$.activeAlertsCount", is(1)))
                .andExpect(jsonPath("$.activeAlertsBySeverity.CRITICAL", is(1)))
                .andExpect(jsonPath("$.activeAnomaliesCount", is(1)))
                .andExpect(jsonPath("$.factoryStatus", is("CRITICAL")))
                .andExpect(jsonPath("$.overallHealthScore", notNullValue()))
                .andExpect(jsonPath("$.lastUpdated", notNullValue()));
    }

    @Test
    @DisplayName("US52: GET /api/dashboard/kpis - Disponibilité, MTBF, MTTR et TRS/OEE (UNAVAILABLE)")
    void testGetKpis() throws Exception {
        mockMvc.perform(get("/api/dashboard/kpis")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("period", "day"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period", is("day")))
                .andExpect(jsonPath("$.availability.value", notNullValue()))
                .andExpect(jsonPath("$.availability.unit", is("%")))
                .andExpect(jsonPath("$.mtbf.valueHours", notNullValue()))
                .andExpect(jsonPath("$.mtbf.unit", is("hours")))
                .andExpect(jsonPath("$.mttr.valueMinutes", notNullValue()))
                .andExpect(jsonPath("$.oee.status", is("UNAVAILABLE")))
                .andExpect(jsonPath("$.oee.value", nullValue()))
                .andExpect(jsonPath("$.oee.reason", containsString("données de production requises pour le calcul du TRS")))
                .andExpect(jsonPath("$.oee.missingMetrics", hasSize(3)))
                .andExpect(jsonPath("$.oee.components.availability.status", is("AVAILABLE")));
    }

    @Test
    @DisplayName("US52: GET /api/dashboard/kpis/history - Série temporelle des indicateurs")
    void testGetKpisHistory() throws Exception {
        mockMvc.perform(get("/api/dashboard/kpis/history")
                        .header("Authorization", "Bearer " + respIndusToken)
                        .param("period", "week")
                        .param("points", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].timestamp", notNullValue()))
                .andExpect(jsonPath("$[0].availability", notNullValue()));
    }

    @Test
    @DisplayName("US53: GET /api/dashboard/factory-state - Synthèse usine et zones")
    void testGetFactoryState() throws Exception {
        mockMvc.perform(get("/api/dashboard/factory-state")
                        .header("Authorization", "Bearer " + respIndusToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.factoryStatus", is("CRITICAL")))
                .andExpect(jsonPath("$.totalZones", is(1)))
                .andExpect(jsonPath("$.totalMachines", is(2)))
                .andExpect(jsonPath("$.zones[0].zoneName", is("Zone Usinage A")))
                .andExpect(jsonPath("$.zones[0].totalMachines", is(2)))
                .andExpect(jsonPath("$.zones[0].zoneStatus", is("CRITICAL")));
    }

    @Test
    @DisplayName("US53: GET /api/dashboard/zones/{zoneId} - Détails de la zone et ses machines")
    void testGetZoneDetails() throws Exception {
        mockMvc.perform(get("/api/dashboard/zones/" + zone1.getId())
                        .header("Authorization", "Bearer " + technicianToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zoneId", is(zone1.getId())))
                .andExpect(jsonPath("$.zoneName", is("Zone Usinage A")))
                .andExpect(jsonPath("$.machines", hasSize(2)))
                .andExpect(jsonPath("$.machines[0].code", notNullValue()));
    }

    @Test
    @DisplayName("US54: GET /api/dashboard/anomalies - Liste avec filtrage et pagination")
    void testGetAnomalies() throws Exception {
        mockMvc.perform(get("/api/dashboard/anomalies")
                        .header("Authorization", "Bearer " + respIndusToken)
                        .param("severity", "CRITICAL")
                        .param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].machineCode", is("TN-02")))
                .andExpect(jsonPath("$.content[0].severity", is("CRITICAL")))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    @DisplayName("US54: GET /api/dashboard/anomalies/stats - Statistiques d'anomalies")
    void testGetAnomaliesStats() throws Exception {
        mockMvc.perform(get("/api/dashboard/anomalies/stats")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("period", "week"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAnomalies", is(1)))
                .andExpect(jsonPath("$.bySeverity.CRITICAL", is(1)))
                .andExpect(jsonPath("$.byStatus.OPEN", is(1)))
                .andExpect(jsonPath("$.bySensorType.TEMPERATURE", is(1)));
    }

    @Test
    @DisplayName("US55: GET /api/dashboard/health-scores - Scores de santé de toutes les machines")
    void testGetHealthScores() throws Exception {
        mockMvc.perform(get("/api/dashboard/health-scores")
                        .header("Authorization", "Bearer " + respIndusToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMachines", is(2)))
                .andExpect(jsonPath("$.averageScore", notNullValue()))
                .andExpect(jsonPath("$.machines", hasSize(2)));
    }

    @Test
    @DisplayName("US55: GET /api/dashboard/health-scores/{machineId} - Détail score de santé machine")
    void testGetMachineHealthScore() throws Exception {
        // Machine 1: RUNNING avec capteurs -> Score élevé (EXCELLENT)
        mockMvc.perform(get("/api/dashboard/health-scores/" + machine1.getId())
                        .header("Authorization", "Bearer " + respIndusToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machineCode", is("CNC-01")))
                .andExpect(jsonPath("$.score", greaterThanOrEqualTo(80)))
                .andExpect(jsonPath("$.grade", is("EXCELLENT")))
                .andExpect(jsonPath("$.penalties.machineStatus", is(0)));

        // Machine 2: FAILURE + CRITICAL ALERT -> Score bas (CRITICAL)
        mockMvc.perform(get("/api/dashboard/health-scores/" + machine2.getId())
                        .header("Authorization", "Bearer " + respIndusToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machineCode", is("TN-02")))
                .andExpect(jsonPath("$.score", lessThan(60)))
                .andExpect(jsonPath("$.penalties.machineStatus", is(40)))
                .andExpect(jsonPath("$.recommendations", notNullValue()));
    }

    @Test
    @DisplayName("US05 / RBAC: OPERATOR reçoit 403 Forbidden sur le Dashboard")
    void testOperatorForbidden() throws Exception {
        mockMvc.perform(get("/api/dashboard/overview")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("US05 / Sécurité: Accès non authentifié renvoie 401 Unauthorized")
    void testUnauthenticatedForbidden() throws Exception {
        mockMvc.perform(get("/api/dashboard/overview"))
                .andExpect(status().isUnauthorized());
    }
}
