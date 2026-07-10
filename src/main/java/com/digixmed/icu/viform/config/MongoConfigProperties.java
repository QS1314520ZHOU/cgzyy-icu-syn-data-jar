package com.digixmed.icu.viform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 多数据源 MongoDB URI 配置绑定。
 *
 * <p>绑定 application.yml 中的 {@code data.mongodb.smartcare} 和 {@code data.mongodb.datacenter}。</p>
 */
@Data
@ConfigurationProperties(prefix = "data.mongodb")
public class MongoConfigProperties {

    /** SmartCare 库（Patient / Bedside 等） */
    private Smartcare smartcare = new Smartcare();

    /** DataCenter 库（Order 等） */
    private Datacenter datacenter = new Datacenter();

    @Data
    public static class Smartcare {
        /** MongoDB 连接 URI（含库名） */
        private String uri;
    }

    @Data
    public static class Datacenter {
        /** MongoDB 连接 URI（含库名） */
        private String uri;
    }
}
