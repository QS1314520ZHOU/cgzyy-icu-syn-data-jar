package com.digixmed.icu.viform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 人大金仓（KingbaseES）只读数据源配置绑定（application.yml 中的 {@code kingbase} 段）。
 */
@Data
@ConfigurationProperties(prefix = "kingbase")
public class KingbaseProperties {

    /** JDBC 连接串 */
    private String url;

    /** 用户名 */
    private String username;

    /** 密码 */
    private String password;

    /** 驱动类 */
    private String driverClassName = "com.kingbase8.Driver";

    /** 连接池最大连接数 */
    private int maximumPoolSize = 5;

    /** 是否只读连接 */
    private boolean readOnly = true;

    /** 连接超时（毫秒） */
    private long connectionTimeoutMs = 5000;
}
