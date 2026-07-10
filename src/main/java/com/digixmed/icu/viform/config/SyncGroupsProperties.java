package com.digixmed.icu.viform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * bedside 定时同步配置绑定（application.yml 中的 {@code sync} 段）。
 *
 * <p>包含：提前量、时区、编辑账号、code→时间点分组映射。</p>
 */
@Data
@ConfigurationProperties(prefix = "sync")
public class SyncGroupsProperties {

    /** 提前量（分钟），默认 5 */
    private int advanceMinutes = 5;

    /** 时区，默认 Asia/Shanghai */
    private String timezone = "Asia/Shanghai";

    /** 系统写入账号 ID */
    private String editUser = "6a1f9f01e467916d244bbdaa";

    /** 同步分组列表 */
    private List<SyncGroup> groups = new ArrayList<>();

    /**
     * 单个同步分组：一组 code 在同一组时间点触发前推补写。
     */
    @Data
    public static class SyncGroup {

        /** 分组名称（标识用） */
        private String name;

        /** 分组描述 */
        private String description;

        /** 触发时间点列表，格式 HH:mm，如 ["02:00","06:00","10:00"] */
        private List<String> times = new ArrayList<>();

        /** 该分组覆盖的 bedside.code 列表 */
        private List<String> codes = new ArrayList<>();
    }
}
