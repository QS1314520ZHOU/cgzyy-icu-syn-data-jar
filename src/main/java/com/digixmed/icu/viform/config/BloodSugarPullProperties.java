package com.digixmed.icu.viform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 血糖拉取配置绑定（application.yml 中的 {@code bloodsugar-pull} 段）。
 */
@Data
@ConfigurationProperties(prefix = "bloodsugar-pull")
public class BloodSugarPullProperties {

    /** 是否启用 */
    private boolean enabled = true;

    /** 时区 */
    private String timezone = "Asia/Shanghai";

    /** 回溯窗口（小时）：每轮拉取 [now-lookbackHours, now] 的录入时间 */
    private int lookbackHours = 24;

    /** 定时扫描间隔（毫秒） */
    private long scanIntervalMs = 300_000;

    /** 启动后延迟多久执行首轮（毫秒） */
    private long initialDelayMs = 60_000;

    /** 住院号 IN 列表分批大小 */
    private int batchSize = 500;

    /** 视图 schema */
    private String viewSchema = "np_nis_cqchonggang";

    /** 视图名 */
    private String viewName = "v_blood_for_zzxt";

    /** 视图列名覆盖（留空=按候选列名自动解析） */
    private Columns columns = new Columns();

    @Data
    public static class Columns {
        /** 姓名（仅日志旁证） */
        private String name;
        /** 住院号 */
        private String inhosNo;
        /** 录入时间 */
        private String recordTime;
        /** 类型 */
        private String type;
        /** 数值 */
        private String value;
        /** 备注 */
        private String note;
        /** 签名 */
        private String signer;
    }
}
