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

    @Bean
    public MongoClient mongoClient() {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(primaryUri))
                .applyToSocketSettings(builder -> builder.connectTimeout(5, TimeUnit.SECONDS))
                .applyToClusterSettings(builder -> builder.serverSelectionTimeout(5, TimeUnit.SECONDS))
                .build();
        return MongoClients.create(settings);
    }

    @Primary
    @Bean(name = "primaryMongoDatabaseFactory")
    public MongoDatabaseFactory primaryMongoDatabaseFactory(MongoClient mongoClient) {
        log.info("Initialisation de la base métier MongoDB: {}", maskUri(primaryUri));
        String databaseName = new ConnectionString(primaryUri).getDatabase();
        if (databaseName == null || databaseName.isBlank()) {
            databaseName = "smartfactory";
        }
        return new SimpleMongoClientDatabaseFactory(mongoClient, databaseName);
    }

    @Primary
    @Bean(name = {"primaryMongoTemplate", "mongoTemplate"})
    public MongoTemplate primaryMongoTemplate(
            @Qualifier("primaryMongoDatabaseFactory") MongoDatabaseFactory factory,
            MongoCustomConversions conversions) {
        return new MongoTemplate(factory, createMappingConverter(factory, conversions));
    }

    @Bean(name = "historyMongoDatabaseFactory")
    public MongoDatabaseFactory historyMongoDatabaseFactory(MongoClient mongoClient) {
        log.info("Initialisation de la base historique dédiée SmartFactoryHistory: {}", maskUri(historyUri));
        String databaseName = new ConnectionString(historyUri).getDatabase();
        if (databaseName == null || databaseName.isBlank()) {
            databaseName = "SmartFactoryHistory";
        }
        return new SimpleMongoClientDatabaseFactory(mongoClient, databaseName);
    }

    @Bean(name = "historyMongoTemplate")
    public MongoTemplate historyMongoTemplate(
            @Qualifier("historyMongoDatabaseFactory") MongoDatabaseFactory factory,
            MongoCustomConversions conversions) {
        return new MongoTemplate(factory, createMappingConverter(factory, conversions));
    }

    public MappingMongoConverter createPublicMappingConverter(MongoDatabaseFactory factory, MongoCustomConversions conversions) {
        return createMappingConverter(factory, conversions);
    }

    private String maskUri(String uri) {
        if (uri == null) return "null";
        return uri.replaceAll(":[^:@]+@", ":****@");
    }
}
