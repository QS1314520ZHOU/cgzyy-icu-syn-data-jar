package com.digixmed.icu.viform.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * SmartCare 库 MongoDB 配置（主数据源，@Primary）。
 *
 * <p>管理集合：patient / bedside / bedside_sync_log。</p>
 */
@Slf4j
@Configuration
@EnableMongoRepositories(
        basePackages = "com.digixmed.icu.viform.repository.smartcare",
        mongoTemplateRef = "smartCareMongoTemplate"
)
public class SmartCareMongoConfig {

    private final MongoConfigProperties properties;

    public SmartCareMongoConfig(MongoConfigProperties properties) {
        this.properties = properties;
    }

    /**
     * SmartCare MongoClient。
     */
    @Primary
    @Bean(name = "smartCareMongoClient")
    public MongoClient smartCareMongoClient() {
        ConnectionString cs = new ConnectionString(properties.getSmartcare().getUri());
        log.info("[MongoDB] 创建 SmartCare MongoClient: hosts={}, db={}",
                cs.getHosts(), cs.getDatabase());
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(cs)
                .build();
        return MongoClients.create(settings);
    }

    /**
     * SmartCare MongoDatabaseFactory。
     */
    @Primary
    @Bean(name = "smartCareMongoDatabaseFactory")
    public MongoDatabaseFactory smartCareMongoDatabaseFactory() {
        ConnectionString cs = new ConnectionString(properties.getSmartcare().getUri());
        log.info("[MongoDB] 创建 SmartCare MongoDatabaseFactory: db={}", cs.getDatabase());
        return new SimpleMongoClientDatabaseFactory(smartCareMongoClient(), cs.getDatabase());
    }

    /**
     * SmartCare MongoTemplate（主模板，@Primary）。
     */
    @Primary
    @Bean(name = "smartCareMongoTemplate")
    public MongoTemplate smartCareMongoTemplate() {
        log.info("[MongoDB] 创建 SmartCare MongoTemplate (主数据源 @Primary)");
        return new MongoTemplate(smartCareMongoDatabaseFactory());
    }
}
