package com.digixmed.icu.viform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 管道护理同步配置绑定（application.yml 中的 {@code tube-nursing-sync} 段）。
 */
@Data
@ConfigurationProperties(prefix = "tube-nursing-sync")
public class TubeNursingSyncProperties {

    /** 是否启用，默认 true */
    private boolean enabled = true;

    /** 时区，默认 Asia/Shanghai */
    private String timezone = "Asia/Shanghai";

    /** 定时扫描间隔（毫秒），默认 5 分钟 */
    private long scanIntervalMs = 300_000;
}
