package com.smartfactory.config;

import com.smartfactory.repository.SensorReadingRepository;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Lie spécifiquement le SensorReadingRepository à la base dédiée SmartFactoryHistory
 * via le template historyMongoTemplate.
 * Exclut tous les autres repositories métier via filtre regex pour éviter les doublons.
 */
@Configuration
@EnableMongoRepositories(
        basePackages = "com.smartfactory.repository",
        includeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {SensorReadingRepository.class}
        ),
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.smartfactory\\.repository\\.(?!SensorReadingRepository).*"
        ),
        mongoTemplateRef = "historyMongoTemplate"
)
public class HistoryMongoRepositoryConfig {
}
