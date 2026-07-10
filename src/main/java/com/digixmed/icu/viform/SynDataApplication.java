package com.digixmed.icu.viform;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用入口。
 *
 * <p>排除 Spring Boot 默认 MongoDB 自动配置，改由 {@code config} 包下
 * {@code SmartCareMongoConfig} / {@code DataCenterMongoConfig} 手动管理双数据源。</p>
 */
@Slf4j
@SpringBootApplication(exclude = {
        MongoAutoConfiguration.class,
        MongoDataAutoConfiguration.class,
        MongoRepositoriesAutoConfiguration.class
})
@ConfigurationPropertiesScan(basePackages = "com.digixmed.icu.viform.config")
@EnableScheduling
public class SynDataApplication {

    public static void main(String[] args) {
        SpringApplication.run(SynDataApplication.class, args);
    }

    /**
     * 容器启动完成后打印关键信息。
     */
    @EventListener(ContextRefreshedEvent.class)
    public void onReady() {
        log.info("========================================");
        log.info("  cgzyy-icu-syn-data-jar 启动完成");
        log.info("  时区: Asia/Shanghai (GMT+8)");
        log.info("  双数据源: SmartCare (主) + DataCenter");
        log.info("  定时调度: @Scheduled 每分钟轮询");
        log.info("  接口: /syn/health /syn/process /syn/param-sync /syn/order-sync");
        log.info("========================================");
    }
}
