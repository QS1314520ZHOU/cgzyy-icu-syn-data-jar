package com.digixmed.icu.viform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 血糖同步配置绑定（application.yml 中的 {@code bloodsugar-sync} 段）。
 */
@Data
@ConfigurationProperties(prefix = "bloodsugar-sync")
public class BloodSugarSyncProperties {

    /** 是否启用，默认 true */
    private boolean enabled = true;

    /** 目标 bedside.code，默认 param_XueTang */
    private String targetCode = "param_XueTang";

    /** 系统写入账号 */
    private String editUser = "6a1f9f01e467916d244bbdaa";

    /** 时区，默认 Asia/Shanghai */
    private String timezone = "Asia/Shanghai";

    /**
     * 回溯窗口（小时）：只处理 time 在 [now - lookbackHours, now] 内的 bloodSugar 记录，
     * 避免全量历史数据重算。
     */
    private int lookbackHours = 48;

    /** 定时扫描间隔（毫秒），默认 5 分钟 */
    private long scanIntervalMs = 300_000;
}
