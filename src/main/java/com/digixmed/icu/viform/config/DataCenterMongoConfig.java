package com.digixmed.icu.viform.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * DataCenter 库 MongoDB 配置。
 *
 * <p>管理集合：VI_ICU_ZYYZ（Order 医嘱）。</p>
 */
@Slf4j
@Configuration
@EnableMongoRepositories(
        basePackages = "com.digixmed.icu.viform.repository.datacenter",
        mongoTemplateRef = "dataCenterMongoTemplate"
)
public class DataCenterMongoConfig {

    private final MongoConfigProperties properties;

    public DataCenterMongoConfig(MongoConfigProperties properties) {
        this.properties = properties;
    }

    /**
     * DataCenter MongoClient。
     */
    @Bean(name = "dataCenterMongoClient")
    public MongoClient dataCenterMongoClient() {
        ConnectionString cs = new ConnectionString(properties.getDatacenter().getUri());
        log.info("[MongoDB] 创建 DataCenter MongoClient: hosts={}, db={}",
                cs.getHosts(), cs.getDatabase());
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(cs)
                .build();
        return MongoClients.create(settings);
    }

    /**
     * DataCenter MongoDatabaseFactory。
     */
    @Bean(name = "dataCenterMongoDatabaseFactory")
    public MongoDatabaseFactory dataCenterMongoDatabaseFactory() {
        ConnectionString cs = new ConnectionString(properties.getDatacenter().getUri());
        log.info("[MongoDB] 创建 DataCenter MongoDatabaseFactory: db={}", cs.getDatabase());
        return new SimpleMongoClientDatabaseFactory(dataCenterMongoClient(), cs.getDatabase());
    }

    /**
     * DataCenter MongoTemplate。
     */
    @Bean(name = "dataCenterMongoTemplate")
    public MongoTemplate dataCenterMongoTemplate() {
        log.info("[MongoDB] 创建 DataCenter MongoTemplate (Order 医嘱库)");
        return new MongoTemplate(dataCenterMongoDatabaseFactory());
    }
}
