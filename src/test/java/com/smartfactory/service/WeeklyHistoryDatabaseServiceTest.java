package com.smartfactory.service;

import com.mongodb.client.MongoClient;
import com.smartfactory.config.MongoConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class WeeklyHistoryDatabaseServiceTest {

    private MongoClient mongoClient;
    private MongoConfig mongoConfig;
    private MongoCustomConversions conversions;
    private WeeklyHistoryDatabaseService service;

    @BeforeEach
    void setUp() {
        mongoClient = Mockito.mock(MongoClient.class);
        mongoConfig = Mockito.mock(MongoConfig.class);
        conversions = Mockito.mock(MongoCustomConversions.class);
        service = new WeeklyHistoryDatabaseService(mongoClient, mongoConfig, conversions);
    }

    @Test
    @DisplayName("2.2 - Weekly database naming follows ISO-8601 pattern SmartFactory_History_YYYY_Www")
    void testGetDatabaseNameForInstant() {
        // 2026-10-20 is Tuesday of Week 43, 2026
        Instant oct20 = LocalDate.of(2026, 10, 20).atStartOfDay().toInstant(ZoneOffset.UTC);
        String dbName = service.getDatabaseNameForInstant(oct20);
        assertThat(dbName).isEqualTo("SmartFactory_History_2026_W43");

        // 2027-01-01 is Friday of Week 53, 2026 (ISO week)
        Instant jan1 = LocalDate.of(2026, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
        String jan1Name = service.getDatabaseNameForInstant(jan1);
        assertThat(jan1Name).matches("^SmartFactory_History_\\d{4}_W\\d{2}$");
    }

    @Test
    @DisplayName("2.2 - getDatabaseNamesForRange returns all weekly databases covering the time interval")
    void testGetDatabaseNamesForRange() {
        Instant from = LocalDate.of(2026, 9, 28).atStartOfDay().toInstant(ZoneOffset.UTC); // W40
        Instant to = LocalDate.of(2026, 10, 20).atStartOfDay().toInstant(ZoneOffset.UTC);   // W43

        Set<String> dbs = service.getDatabaseNamesForRange(from, to);
        assertThat(dbs).isNotEmpty();
        assertThat(dbs).contains("SmartFactory_History_2026_W40", "SmartFactory_History_2026_W43");
    }

    @Test
    @DisplayName("2.2 - 3-Month retention policy simulates expiration without dropping active or protected DBs")
    void testRetentionSimulationSafeguards() {
        com.mongodb.client.MongoIterable<String> iterable = Mockito.mock(com.mongodb.client.MongoIterable.class);
        com.mongodb.client.MongoCursor<String> cursor = Mockito.mock(com.mongodb.client.MongoCursor.class);
        List<String> dbs = List.of(
                "smartfactory",                   // OPERATIONAL: NEVER DROP
                "SmartFactoryHistory",            // LEGACY: NEVER DROP
                "SmartFactory_History_2025_W10",  // OLD (> 1 year): SHOULD BE IDENTIFIED FOR DROP
                service.getCurrentWeeklyDatabaseName() // CURRENT: NEVER DROP
        );
        java.util.Iterator<String> it = dbs.iterator();
        when(cursor.hasNext()).thenAnswer(inv -> it.hasNext());
        when(cursor.next()).thenAnswer(inv -> it.next());
        when(iterable.iterator()).thenReturn(cursor);
        when(mongoClient.listDatabaseNames()).thenReturn(iterable);

        List<String> toDelete = service.simulateRetentionCleanup();
        assertThat(toDelete).contains("SmartFactory_History_2025_W10");
        assertThat(toDelete).doesNotContain("smartfactory");
        assertThat(toDelete).doesNotContain("SmartFactoryHistory");
        assertThat(toDelete).doesNotContain(service.getCurrentWeeklyDatabaseName());
    }
}
