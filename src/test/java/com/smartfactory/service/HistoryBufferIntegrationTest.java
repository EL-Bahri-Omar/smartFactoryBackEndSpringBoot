package com.smartfactory.service;

import com.smartfactory.entity.SensorReading;
import com.smartfactory.entity.SensorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class HistoryBufferIntegrationTest {

    @Autowired
    private HistoryBufferService bufferService;

    @Autowired
    @Qualifier("historyMongoTemplate")
    private MongoTemplate historyMongoTemplate;

    @BeforeEach
    void setUp() {
        historyMongoTemplate.dropCollection(SensorReading.class);
        bufferService.setImmediatePersist(false); // Mode batch différé 20s
        bufferService.setBufferCapacity(100);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        bufferService.setImmediatePersist(true);
        bufferService.setBufferCapacity(10000);
        bufferService.flushBuffer();
    }

    @Test
    @DisplayName("Part B2: Enqueued readings stay in buffer and are persisted into SmartFactoryHistory on flush")
    void testBufferBatchFlush() {
        Instant now = Instant.now();
        SensorReading r1 = new SensorReading("m-1", "CNC-001", "s-1", SensorType.TEMPERATURE, 45.2, "°C", now, now);
        SensorReading r2 = new SensorReading("m-1", "CNC-001", "s-2", SensorType.VIBRATION, 2.1, "mm/s", now, now);
        SensorReading r3 = new SensorReading("m-2", "CNC-002", "s-3", SensorType.CURRENT, 8.5, "A", now, now);

        bufferService.enqueue(r1);
        bufferService.enqueue(r2);
        bufferService.enqueue(r3);

        // Avant le vidage : les mesures sont dans le buffer et NON ENCORE dans MongoDB
        assertThat(bufferService.getBufferSize()).isEqualTo(3);
        assertThat(historyMongoTemplate.findAll(SensorReading.class)).isEmpty();

        // Déclenchement du vidage par lots (équivalent au cycle 20 secondes)
        int flushed = bufferService.flushBuffer();
        assertThat(flushed).isEqualTo(3);
        assertThat(bufferService.getBufferSize()).isEqualTo(0);

        // Après le vidage : les documents sont bien présents dans SmartFactoryHistory
        List<SensorReading> stored = historyMongoTemplate.findAll(SensorReading.class);
        assertThat(stored).hasSize(3);
        assertThat(stored).extracting(SensorReading::getSensorType)
                .containsExactlyInAnyOrder(SensorType.TEMPERATURE, SensorType.VIBRATION, SensorType.CURRENT);
    }

    @Test
    @DisplayName("Part B2: Bounded buffer capacity evicts oldest item when full without crashing")
    void testBoundedBufferOverflowPolicy() {
        bufferService.setBufferCapacity(5); // Petite capacité pour test

        Instant now = Instant.now();
        for (int i = 1; i <= 8; i++) {
            SensorReading r = new SensorReading("m-1", "CNC-001", "s-1", SensorType.RPM, (double) (1000 + i), "RPM", now, now);
            bufferService.enqueue(r);
        }

        // Le buffer ne dépasse jamais sa capacité maximale configurée
        assertThat(bufferService.getBufferSize()).isEqualTo(5);
        // 3 éléments les plus anciens ont été évincés de façon observable
        assertThat(bufferService.getTotalDroppedCount()).isEqualTo(3);

        bufferService.flushBuffer();
        List<SensorReading> stored = historyMongoTemplate.findAll(SensorReading.class);
        assertThat(stored).hasSize(5);
        // Les valeurs persistées sont les plus récentes (1004 à 1008)
        assertThat(stored).extracting(SensorReading::getValue)
                .contains(1008.0, 1007.0, 1006.0, 1005.0, 1004.0);
    }

    @Test
    @DisplayName("Part B2: Graceful shutdown flushes remaining items in buffer")
    void testGracefulShutdownFlush() {
        Instant now = Instant.now();
        bufferService.enqueue(new SensorReading("m-1", "CNC-001", "s-1", SensorType.TEMPERATURE, 50.0, "°C", now, now));
        bufferService.enqueue(new SensorReading("m-1", "CNC-001", "s-2", SensorType.CURRENT, 9.0, "A", now, now));

        assertThat(bufferService.getBufferSize()).isEqualTo(2);

        // Simulation de l'arrêt du backend
        bufferService.onShutdown();

        assertThat(bufferService.getBufferSize()).isEqualTo(0);
        assertThat(historyMongoTemplate.findAll(SensorReading.class)).hasSize(2);
    }
}
