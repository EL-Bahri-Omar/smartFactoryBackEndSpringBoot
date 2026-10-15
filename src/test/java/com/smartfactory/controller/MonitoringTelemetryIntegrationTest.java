package com.smartfactory.controller;

import com.smartfactory.entity.*;
import com.smartfactory.repository.MachineRepository;
import com.smartfactory.repository.SensorReadingRepository;
import com.smartfactory.repository.UserRepository;
import com.smartfactory.security.JwtService;
import com.smartfactory.security.Role;
import com.smartfactory.service.SensorReadingService;
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
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class MonitoringTelemetryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MachineRepository machineRepository;

    @Autowired
    private SensorReadingRepository readingRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SensorReadingService readingService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String token;
    private Machine machine;

    @BeforeEach
    void setUp() {
        readingRepository.deleteAll();
        machineRepository.deleteAll();
        userRepository.deleteAll();

        // Create operator user with telemetry privileges
        User operator = new User("Operator", "Telemetry", "operator.telemetry@smartfactory.com",
                passwordEncoder.encode("secret"), Role.OPERATOR, "ACTIVE");
        operator = userRepository.save(operator);
        token = jwtService.generateToken(operator);

        machine = new Machine(
                "Centre d'usinage CNC-024",
                "CNC-024",
                "Fraiseuse",
                null,
                MachineStatus.RUNNING,
                "Machine de test",
                List.of()
        );
        machine = machineRepository.save(machine);
    }

    @Test
    @DisplayName("US20: Real-time temperature consultation with threshold indicators")
    void testGetTemperatureReading() throws Exception {
        // Simuler la réception d'une mesure de température
        readingService.validateAndProcess("factory/machines/CNC-024/temperature", """
                {
                  "machineId": "CNC-024",
                  "sensorType": "TEMPERATURE",
                  "value": 45.5,
                  "unit": "°C",
                  "timestamp": "2026-10-15T10:00:00.000Z"
                }
                """);

        mockMvc.perform(get("/api/machines/CNC-024/sensors/temperature/current")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machineCode").value("CNC-024"))
                .andExpect(jsonPath("$.sensorType").value("TEMPERATURE"))
                .andExpect(jsonPath("$.value").value(45.5))
                .andExpect(jsonPath("$.unit").value("°C"))
                .andExpect(jsonPath("$.status").value("NORMAL"))
                .andExpect(jsonPath("$.thresholdBreached").value(false));
    }

    @Test
    @DisplayName("US21: Real-time vibrations consultation")
    void testGetVibrationReading() throws Exception {
        readingService.validateAndProcess("factory/machines/CNC-024/vibration", """
                {
                  "machineId": "CNC-024",
                  "sensorType": "VIBRATION",
                  "value": 2.35,
                  "unit": "mm/s",
                  "timestamp": "2026-10-15T10:00:00.000Z"
                }
                """);

        mockMvc.perform(get("/api/machines/CNC-024/sensors/vibration/current")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sensorType").value("VIBRATION"))
                .andExpect(jsonPath("$.value").value(2.35))
                .andExpect(jsonPath("$.unit").value("mm/s"));
    }

    @Test
    @DisplayName("US22: Real-time electric current consultation")
    void testGetCurrentReading() throws Exception {
        readingService.validateAndProcess("factory/machines/CNC-024/current", """
                {
                  "machineId": "CNC-024",
                  "sensorType": "CURRENT",
                  "value": 8.75,
                  "unit": "A",
                  "timestamp": "2026-10-15T10:00:00.000Z"
                }
                """);

        mockMvc.perform(get("/api/machines/CNC-024/sensors/current/current")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sensorType").value("CURRENT"))
                .andExpect(jsonPath("$.value").value(8.75))
                .andExpect(jsonPath("$.unit").value("A"));
    }

    @Test
    @DisplayName("US23: Real-time RPM consultation")
    void testGetRpmReading() throws Exception {
        readingService.validateAndProcess("factory/machines/CNC-024/rpm", """
                {
                  "machineId": "CNC-024",
                  "sensorType": "RPM",
                  "value": 1500,
                  "unit": "RPM",
                  "timestamp": "2026-10-15T10:00:00.000Z"
                }
                """);

        mockMvc.perform(get("/api/machines/CNC-024/sensors/rpm/current")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sensorType").value("RPM"))
                .andExpect(jsonPath("$.value").value(1500))
                .andExpect(jsonPath("$.unit").value("RPM"));
    }

    @Test
    @DisplayName("US24: Machine state consultation and manual state update")
    void testMachineStateManagement() throws Exception {
        // Consult status
        mockMvc.perform(get("/api/machines/CNC-024/status")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.isOnline").value(true));

        // Create admin user to update status
        User admin = new User("Admin", "User", "admin.state@smartfactory.com",
                passwordEncoder.encode("secret"), Role.ADMIN, "ACTIVE");
        admin = userRepository.save(admin);
        String adminToken = jwtService.generateToken(admin);

        // Update to MAINTENANCE
        mockMvc.perform(put("/api/machines/CNC-024/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"MAINTENANCE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MAINTENANCE"));

        mockMvc.perform(get("/api/machines/CNC-024/status")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MAINTENANCE"))
                .andExpect(jsonPath("$.isOnline").value(false));
    }

    @Test
    @DisplayName("US25: Reconnection summary API returns all sensor readings for client re-synchronization")
    void testLatestTelemetrySummary() throws Exception {
        readingService.validateAndProcess("factory/machines/CNC-024/temperature",
                "{\"machineId\":\"CNC-024\",\"sensorType\":\"TEMPERATURE\",\"value\":45.0,\"unit\":\"°C\",\"timestamp\":\"2026-10-15T10:00:00Z\"}");
        readingService.validateAndProcess("factory/machines/CNC-024/vibration",
                "{\"machineId\":\"CNC-024\",\"sensorType\":\"VIBRATION\",\"value\":2.1,\"unit\":\"mm/s\",\"timestamp\":\"2026-10-15T10:00:00Z\"}");
        readingService.validateAndProcess("factory/machines/CNC-024/current",
                "{\"machineId\":\"CNC-024\",\"sensorType\":\"CURRENT\",\"value\":8.2,\"unit\":\"A\",\"timestamp\":\"2026-10-15T10:00:00Z\"}");
        readingService.validateAndProcess("factory/machines/CNC-024/rpm",
                "{\"machineId\":\"CNC-024\",\"sensorType\":\"RPM\",\"value\":1500,\"unit\":\"RPM\",\"timestamp\":\"2026-10-15T10:00:00Z\"}");

        mockMvc.perform(get("/api/machines/CNC-024/readings/latest")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machineCode").value("CNC-024"))
                .andExpect(jsonPath("$.sensors.temperature.value").value(45.0))
                .andExpect(jsonPath("$.sensors.vibration.value").value(2.1))
                .andExpect(jsonPath("$.sensors.current.value").value(8.2))
                .andExpect(jsonPath("$.sensors.rpm.value").value(1500));
    }

    @Test
    @DisplayName("US26 & US28: History consultation with invalid period rejects with 400 Bad Request")
    void testInvalidPeriodRejected() throws Exception {
        Instant from = Instant.now();
        Instant to = from.minus(2, ChronoUnit.HOURS); // Inverted bounds!

        mockMvc.perform(get("/api/machines/CNC-024/readings")
                        .header("Authorization", "Bearer " + token)
                        .param("from", from.toString())
                        .param("to", to.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("US27 & US28: Time-series endpoint returns chronological points for charts with 24h quick period")
    void testTimeseriesChartsEndpoint() throws Exception {
        Instant now = Instant.now();
        readingRepository.save(new SensorReading(machine.getId(), "CNC-024", "s-1", SensorType.TEMPERATURE, 42.0, "°C", now.minus(50, ChronoUnit.MINUTES), now));
        readingRepository.save(new SensorReading(machine.getId(), "CNC-024", "s-1", SensorType.TEMPERATURE, 44.5, "°C", now.minus(30, ChronoUnit.MINUTES), now));
        readingRepository.save(new SensorReading(machine.getId(), "CNC-024", "s-1", SensorType.TEMPERATURE, 46.0, "°C", now.minus(10, ChronoUnit.MINUTES), now));

        mockMvc.perform(get("/api/machines/CNC-024/readings/timeseries")
                        .header("Authorization", "Bearer " + token)
                        .param("sensorType", "TEMPERATURE")
                        .param("period", "24h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machineCode").value("CNC-024"))
                .andExpect(jsonPath("$.sensorType").value("TEMPERATURE"))
                .andExpect(jsonPath("$.period").value("24h"))
                .andExpect(jsonPath("$.totalPoints").value(3))
                .andExpect(jsonPath("$.points[0].value").value(42.0))
                .andExpect(jsonPath("$.points[2].value").value(46.0));
    }
}
