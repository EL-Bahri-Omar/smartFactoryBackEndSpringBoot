package com.smartfactory.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.DbRefResolver;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import java.util.concurrent.TimeUnit;

import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import java.util.Collections;

/**
 * Configuration multi-bases MongoDB pour SmartFactory (Partie B).
 * Sépare strictement :
 * 1. Base métier principale (smartfactory) : Utilisateurs, Machines, Alertes, Groupes, Zones.
 * 2. Base historique dédiée (SmartFactoryHistory) : Conservation des mesures IoT simulées.
 */
@Configuration
public class MongoConfig {

    private static final Logger log = LoggerFactory.getLogger(MongoConfig.class);

    @Value("${spring.data.mongodb.uri:mongodb://localhost:27017/smartfactory}")
    private String primaryUri;

    @Value("${mongodb.history.uri:mongodb://localhost:27017/SmartFactoryHistory}")
    private String historyUri;

    @Bean
    public MongoCustomConversions mongoCustomConversions() {
        return new MongoCustomConversions(Collections.emptyList());
    }

    private MappingMongoConverter createMappingConverter(MongoDatabaseFactory factory, MongoCustomConversions conversions) {
        DbRefResolver dbRefResolver = new DefaultDbRefResolver(factory);
        MongoMappingContext mappingContext = new MongoMappingContext();
        mappingContext.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        mappingContext.afterPropertiesSet();

        MappingMongoConverter converter = new MappingMongoConverter(dbRefResolver, mappingContext);
        converter.setCustomConversions(conversions);
        converter.setTypeMapper(new DefaultMongoTypeMapper(null));
        converter.afterPropertiesSet();
        return converter;
    }

    @Primary
    @Bean(name = "primaryMongoDatabaseFactory")
    public MongoDatabaseFactory primaryMongoDatabaseFactory() {
        log.info("Initialisation de la base métier MongoDB: {}", maskUri(primaryUri));
        return new SimpleMongoClientDatabaseFactory(primaryUri);
    }

    @Primary
    @Bean(name = {"primaryMongoTemplate", "mongoTemplate"})
    public MongoTemplate primaryMongoTemplate(
            @Qualifier("primaryMongoDatabaseFactory") MongoDatabaseFactory factory,
            MongoCustomConversions conversions) {
        return new MongoTemplate(factory, createMappingConverter(factory, conversions));
    }

    @Bean(name = "historyMongoDatabaseFactory")
    public MongoDatabaseFactory historyMongoDatabaseFactory() {
        log.info("Initialisation de la base historique dédiée SmartFactoryHistory: {}", maskUri(historyUri));
        try {
            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(new ConnectionString(historyUri))
                    .applyToSocketSettings(builder -> builder.connectTimeout(5, TimeUnit.SECONDS))
                    .applyToClusterSettings(builder -> builder.serverSelectionTimeout(5, TimeUnit.SECONDS))
                    .build();
            MongoClient client = MongoClients.create(settings);
            String databaseName = new ConnectionString(historyUri).getDatabase();
            if (databaseName == null || databaseName.isBlank()) {
                databaseName = "SmartFactoryHistory";
            }
            return new SimpleMongoClientDatabaseFactory(client, databaseName);
        } catch (Exception ex) {
            log.warn("Impossible de connecter immédiatement la base historique SmartFactoryHistory: {}. Utilisation de la configuration par défaut.",
                    ex.getMessage());
            return new SimpleMongoClientDatabaseFactory(historyUri);
        }
    }

    @Bean(name = "historyMongoTemplate")
    public MongoTemplate historyMongoTemplate(
            @Qualifier("historyMongoDatabaseFactory") MongoDatabaseFactory factory,
            MongoCustomConversions conversions) {
        return new MongoTemplate(factory, createMappingConverter(factory, conversions));
    }

    private String maskUri(String uri) {
        if (uri == null) return "null";
        return uri.replaceAll(":[^:@]+@", ":****@");
    }
}
