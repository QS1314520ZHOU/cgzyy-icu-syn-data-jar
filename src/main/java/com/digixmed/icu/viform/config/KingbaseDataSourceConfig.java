package com.digixmed.icu.viform.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 人大金仓（KingbaseES）只读数据源配置。
 *
 * <p>手动管理 HikariCP 数据源与 JdbcTemplate 命名 Bean，
 * 与项目双 Mongo 数据源的"数据源自管"风格一致（启动类已 exclude JDBC 自动配置）。</p>
 */
@Slf4j
@Configuration
public class KingbaseDataSourceConfig {

    private final KingbaseProperties properties;

    public KingbaseDataSourceConfig(KingbaseProperties properties) {
        this.properties = properties;
    }

    /**
     * 人大金仓只读数据源。
     */
    @Bean(name = "kingbaseDataSource")
    public DataSource kingbaseDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.getUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setDriverClassName(properties.getDriverClassName());
        config.setMaximumPoolSize(properties.getMaximumPoolSize());
        config.setMinimumIdle(1);
        config.setReadOnly(properties.isReadOnly());
        config.setConnectionTimeout(properties.getConnectionTimeoutMs());
        config.setPoolName("kingbase-pool");
        log.info("[Kingbase] 创建只读数据源: url={}, poolSize={}",
                properties.getUrl(), properties.getMaximumPoolSize());
        return new HikariDataSource(config);
    }

    /**
     * 人大金仓 JdbcTemplate（命名 Bean，供 KingbaseBloodSugarViewRepository 使用）。
     */
    @Bean(name = "kingbaseJdbcTemplate")
    public JdbcTemplate kingbaseJdbcTemplate() {
        return new JdbcTemplate(kingbaseDataSource());
    }
}
