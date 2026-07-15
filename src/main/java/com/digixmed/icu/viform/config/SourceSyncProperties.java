package com.digixmed.icu.viform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 源 code 驱动的同时间点跨 code 值联动同步配置。
 *
 * <p>绑定 application.yml 的 {@code source-sync} 段。</p>
 *
 * <p>业务含义：当某患者在某时间点 T 存在 triggerCode 的有效数据时，
 * 把同一 T 的 sourceCodes（按优先级）的值复制到 targetCode。</p>
 */
@Data
@ConfigurationProperties(prefix = "source-sync")
public class SourceSyncProperties {

    /** 是否启用，默认 true */
    private boolean enabled = true;

    /** 系统写入账号 */
    private String editUser = "6a1f9f01e467916d244bbdaa";

    /** 时区 */
    private String timezone = "Asia/Shanghai";

    /**
     * 回溯窗口（分钟）：只处理触发记录 time 在 [now - lookbackMinutes, now] 内的时间点。
     * 避免每次扫描处理全量历史数据。
     */
    private int lookbackMinutes = 180;

    /**
     * 源记录与触发时间点 T 的匹配容差（秒），0 表示精确相等。
     */
    private int timeToleranceSeconds = 0;

    /** 调度器扫描间隔（毫秒），默认 5 分钟 */
    private long scanIntervalMs = 300_000;

    /** 联动规则列表 */
    private List<Rule> rules = new ArrayList<>();

    // ==================== 内部类 ====================

    /**
     * 单条联动规则。
     */
    @Data
    public static class Rule {

        /** 规则名称（标识用） */
        private String name;

        /**
         * 触发 code：当该 code 在时间点 T 存在有效记录时，执行本规则的映射。
         */
        private String triggerCode;

        /** 映射列表 */
        private List<Mapping> mappings = new ArrayList<>();
    }

    /**
     * 单条映射：把源 code 的值复制到目标 code。
     */
    @Data
    public static class Mapping {

        /** 目标 bedside.code */
        private String targetCode;

        /**
         * 源 code 列表，按优先级排序：取第一个在时间点 T 有有效值者（有创优先）。
         */
        private List<String> sourceCodes = new ArrayList<>();
    }
}
