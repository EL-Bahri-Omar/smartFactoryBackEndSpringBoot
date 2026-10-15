package com.smartfactory.config;

import com.smartfactory.repository.SensorReadingRepository;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Lie tous les repositories métier de com.smartfactory.repository à la base métier principale,
 * à l'exclusion du SensorReadingRepository qui est géré par la base historique SmartFactoryHistory.
 */
@Configuration
@EnableMongoRepositories(
        basePackages = "com.smartfactory.repository",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {SensorReadingRepository.class}
        ),
        mongoTemplateRef = "primaryMongoTemplate"
)
public class PrimaryMongoRepositoryConfig {
}
