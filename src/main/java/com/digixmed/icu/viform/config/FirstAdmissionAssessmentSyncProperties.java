package com.digixmed.icu.viform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

/**
 * 首次入科评估同步配置（application.yml 中的 {@code first-assessment-sync} 段）。
 */
@Data
@ConfigurationProperties(prefix = "first-assessment-sync")
public class FirstAdmissionAssessmentSyncProperties {

    /** 是否启用 */
    private boolean enabled = true;

    /** 时区 */
    private String timezone = "Asia/Shanghai";

    /** 扫描间隔（毫秒） */
    private long scanIntervalMs = 300_000;

    /** 目标值与源值不同时，用第一次评估源值更新目标值 */
    private boolean overwriteExisting = true;

    /** 目标值与源值一致时不执行数据库更新（默认必须为 true） */
    private boolean updateOnlyWhenChanged = true;

    /** 评分类型 */
    private String scoreType = "patientFallDangerLJRMYY";

    /** 目标表单编码列表 */
    private List<String> formCodes = Arrays.asList("ruyuanhulipinggudan", "zhuanruhulipinggudan");

    /**
     * 临床判定法选项编码（List<String> 值）。
     * <p>必须从真实表单定义确认，不能猜测。为空时跳过 lcpdf 同步。</p>
     */
    private String clinicalMethodValue;

    /** 需要查询的 bedside code 列表 */
    private List<String> bedsideCodes = Arrays.asList(
            "param_tengTong_score",
            "param_yaChuang_score",
            "param_score_adl",
            "param_score_dght"
    );
}
