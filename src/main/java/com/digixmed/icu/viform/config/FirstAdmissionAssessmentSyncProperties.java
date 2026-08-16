package com.digixmed.icu.viform.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * 首次入科评估同步配置（application.yml 中的 {@code first-assessment-sync} 段）。
 *
 * <p>核心配置：每个 formCode 独立配置字段编码和选项编码，
 * 不允许猜测或硬编码，必须从真实表单定义/现有数据确认。</p>
 */
@Slf4j
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

    /** 需要查询的 bedside code 列表 */
    private List<String> bedsideCodes = Arrays.asList(
            "param_tengTong_score",
            "param_yaChuang_score",
            "param_score_adl",
            "param_score_unPlannedCGZYY",
            "param_nibp_s",
            "param_nibp_d",
            "param_T",
            "param_脉搏",
            "param_HR",
            "param_resp",
            "param_Yishi"
    );

    /**
     * 按 formCode 分别配置的字段编码和选项编码。
     * <p>每个 formCode 必须独立配置，字段编码和选项编码可能不同。</p>
     */
    private Map<String, FormOptionConfig> formOptionConfigs = new LinkedHashMap<>();

    // ── 旧配置兼容（deprecated） ────────────────────────────────────

    /** @deprecated 使用 formOptionConfigs.{formCode}.fallMethodOptions.临床判定法 代替 */
    @Deprecated
    private Map<String, String> clinicalMethodValues = new HashMap<>();

    /** @deprecated 使用 formOptionConfigs.{formCode}.fallMethodOptions.临床判定法 代替 */
    @Deprecated
    private String clinicalMethodValue;

    // ── 内部配置类 ──────────────────────────────────────────────────

    /**
     * 单个表单的选项配置。
     */
    @Data
    public static class FormOptionConfig {

        /**
         * 生活自理能力字段编码（如 "shzlnl"）。
         * <p>必须从目标表单定义确认，不能猜测。</p>
         */
        private String dependencyField;

        /**
         * 依赖程度中文名称 → 数据库 option value 映射。
         * <p>value 必须是目标表单选项的真实内部编码，不能是中文名称或 Java 字段名。</p>
         */
        private Map<String, String> dependencyOptions = new LinkedHashMap<>();

        /**
         * 跌倒评估方法字段编码。
         * <p>临床判定法和 Morse 评分量表的结果合并写入此字段（List）。</p>
         * <p>必须从目标表单定义确认。如果两个方法实际对应不同字段，需在此配置为逗号分隔的多字段。</p>
         */
        private String fallMethodField;

        /**
         * 跌倒评估方法中文名称 → 数据库 option value 映射。
         * <p>value 必须是目标表单选项的真实内部编码。</p>
         */
        private Map<String, String> fallMethodOptions = new LinkedHashMap<>();

        /**
         * 获取 fallMethodField 列表（支持逗号分隔的多字段配置）。
         */
        public List<String> getFallMethodFieldList() {
            if (!StringUtils.hasText(fallMethodField)) {
                return Collections.emptyList();
            }
            List<String> fields = new ArrayList<>();
            for (String f : fallMethodField.split(",")) {
                String trimmed = f.trim();
                if (!trimmed.isEmpty()) {
                    fields.add(trimmed);
                }
            }
            return fields;
        }

        /**
         * 设置 dependencyOptions，处理空值情况。
         * 当环境变量未设置时，Spring会将占位符解析为空字符串，需要过滤掉空值。
         */
        public void setDependencyOptions(Map<String, String> options) {
            if (options == null) {
                this.dependencyOptions = new LinkedHashMap<>();
                return;
            }
            // 过滤掉值为空的选项
            this.dependencyOptions = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : options.entrySet()) {
                if (StringUtils.hasText(entry.getValue())) {
                    this.dependencyOptions.put(entry.getKey(), entry.getValue());
                } else {
                    log.warn("[FirstAssessmentSync] 过滤空值选项: {} = {}", entry.getKey(), entry.getValue());
                }
            }
        }

        /**
         * 设置 fallMethodOptions，处理空值情况。
         */
        public void setFallMethodOptions(Map<String, String> options) {
            if (options == null) {
                this.fallMethodOptions = new LinkedHashMap<>();
                return;
            }
            // 过滤掉值为空的选项
            this.fallMethodOptions = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : options.entrySet()) {
                if (StringUtils.hasText(entry.getValue())) {
                    this.fallMethodOptions.put(entry.getKey(), entry.getValue());
                } else {
                    log.warn("[FirstAssessmentSync] 过滤空值选项: {} = {}", entry.getKey(), entry.getValue());
                }
            }
        }
    }

    // ── 配置校验 ──────────────────────────────────────────────────────

    /**
     * 校验配置完整性。
     * <p>在服务启动时调用，输出清晰的错误/警告日志。</p>
     *
     * @return 校验是否通过（有错误但不阻断启动，跳过未配置的字段）
     */
    public boolean validate() {
        boolean valid = true;

        if (formOptionConfigs == null || formOptionConfigs.isEmpty()) {
            log.warn("[FirstAssessmentSync] formOptionConfigs 未配置，选择类字段将不会同步");
            return false;
        }

        for (Map.Entry<String, FormOptionConfig> entry : formOptionConfigs.entrySet()) {
            String formCode = entry.getKey();
            FormOptionConfig config = entry.getValue();

            if (config == null) {
                log.warn("[FirstAssessmentSync] formCode={} 的 formOptionConfig 为空", formCode);
                valid = false;
                continue;
            }

            // 校验生活自理能力
            if (!StringUtils.hasText(config.getDependencyField())) {
                log.warn("[FirstAssessmentSync] formCode={} 缺少 dependencyField 配置", formCode);
                valid = false;
            } else if (config.getDependencyOptions() == null || config.getDependencyOptions().isEmpty()) {
                log.warn("[FirstAssessmentSync] formCode={} 的 dependencyOptions 为空，将跳过生活自理能力同步", formCode);
                valid = false;
            } else {
                for (Map.Entry<String, String> opt : config.getDependencyOptions().entrySet()) {
                    if (!StringUtils.hasText(opt.getValue())) {
                        log.warn("[FirstAssessmentSync] formCode={} 的 dependencyOptions[{}] 编码为空",
                                formCode, opt.getKey());
                        valid = false;
                    } else if (opt.getKey().equals(opt.getValue())) {
                        log.warn("[FirstAssessmentSync] formCode={} 的 dependencyOptions[{}] 编码与中文名称相同，疑似未配置真实编码",
                                formCode, opt.getKey());
                    }
                }
            }

            // 校验跌倒评估方法
            if (!StringUtils.hasText(config.getFallMethodField())) {
                log.warn("[FirstAssessmentSync] formCode={} 缺少 fallMethodField 配置", formCode);
                valid = false;
            } else if (config.getFallMethodOptions() == null || config.getFallMethodOptions().isEmpty()) {
                log.warn("[FirstAssessmentSync] formCode={} 的 fallMethodOptions 为空，将跳过跌倒评估方法同步", formCode);
                valid = false;
            } else {
                for (Map.Entry<String, String> opt : config.getFallMethodOptions().entrySet()) {
                    if (!StringUtils.hasText(opt.getValue())) {
                        log.warn("[FirstAssessmentSync] formCode={} 的 fallMethodOptions[{}] 编码为空",
                                formCode, opt.getKey());
                        valid = false;
                    } else if (opt.getKey().equals(opt.getValue())) {
                        log.warn("[FirstAssessmentSync] formCode={} 的 fallMethodOptions[{}] 编码与中文名称相同，疑似未配置真实编码",
                                formCode, opt.getKey());
                    }
                }
            }
        }

        // 检查 formCodes 中是否有未配置的
        if (formCodes != null) {
            for (String fc : formCodes) {
                if (!formOptionConfigs.containsKey(fc)) {
                    log.warn("[FirstAssessmentSync] formCode={} 在 form-codes 列表中但无 formOptionConfig 配置", fc);
                }
            }
        }

        return valid;
    }
}
