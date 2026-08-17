package com.digixmed.icu.viform.service;

import com.digixmed.icu.viform.config.FirstAdmissionAssessmentSyncProperties;
import com.digixmed.icu.viform.config.FirstAdmissionAssessmentSyncProperties.FormOptionConfig;
import com.digixmed.icu.viform.entity.Bedside;
import com.digixmed.icu.viform.entity.Score;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 从 bedside/score 中选择入科后第一次有效评估的逻辑。
 *
 * <p>按 (pid, code) 分组后取 time 升序第一条。</p>
 *
 * <p>选择类字段（生活自理能力、跌倒评估方法）的字段编码和选项编码
 * 完全由配置驱动，不允许猜测或硬编码。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FirstAssessmentSourceSelector {

    // ── 评分字段：只同步数值分数（不含括号结论） ──────────────────────

    /** bedside code → 表单字段（仅分数） */
    private static final Map<String, String> SCORE_FIELD_MAPPING = new LinkedHashMap<>();
    static {
        SCORE_FIELD_MAPPING.put("param_yaChuang_score", "braden");   // Braden 压疮评分
        SCORE_FIELD_MAPPING.put("param_score_adl",      "shzlnl");   // Barthel 日常生活活动（生活自理能力）
        SCORE_FIELD_MAPPING.put("param_score_unPlannedCGZYY",     "dght");     // 管道滑脱评估
    }

    /** 匹配数字（整数、小数、负数） */
    private static final Pattern SCORE_PATTERN = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");

    // ── 血压 bedside code ────────────────────────────────────────────

    /** 收缩压 */
    public static final String CODE_NIBP_S = "param_nibp_s";
    /** 舒张压 */
    public static final String CODE_NIBP_D = "param_nibp_d";

    /** 血压目标表单字段：收缩压/舒张压合并写入此单字段 */
    public static final String FIELD_XY = "xy";

    /** 血压合成分隔符 */
    private static final String BP_SEPARATOR = "/";

    // ── 其他 bedside code → 表单字段 ────────────────────────────────

    private static final Map<String, String[]> BEDSIDE_CODE_MAPPING = new LinkedHashMap<>();
    static {
        BEDSIDE_CODE_MAPPING.put("param_tengTong_score", new String[]{"ttpf"});
    }

    // ── 意识状态：中文 → 拼音编码 ────────────────────────────────────

    private static final Map<String, String> CONSCIOUSNESS_MAP = new LinkedHashMap<>();
    static {
        CONSCIOUSNESS_MAP.put("清楚",   "qingchu");
        CONSCIOUSNESS_MAP.put("昏睡",   "hunshui");
        CONSCIOUSNESS_MAP.put("嗜睡",   "shishui");
        CONSCIOUSNESS_MAP.put("轻度昏迷", "qingduhunmi");
        CONSCIOUSNESS_MAP.put("中度昏迷", "zhongduhunmi");
        CONSCIOUSNESS_MAP.put("深度昏迷", "shenduhunmi");
    }

    private final FirstAdmissionAssessmentSyncProperties properties;

    /**
     * 从批量查询结果中按 (pid, code) 选择入科后第一次有效 bedside。
     *
     * @param bedsides 该批次所有相关 bedside 记录
     * @param icuAdmissionTimes pid → icuAdmissionTime 映射
     * @return pid → code → 第一次有效 bedside
     */
    public Map<String, Map<String, Bedside>> selectFirstBedsidePerPidAndCode(
            List<Bedside> bedsides,
            Map<String, Date> icuAdmissionTimes) {

        // 按 pid → code 分组
        Map<String, Map<String, List<Bedside>>> grouped = new HashMap<>();
        for (Bedside b : bedsides) {
            if (b.getPid() == null || b.getCode() == null) continue;
            grouped.computeIfAbsent(b.getPid(), k -> new HashMap<>())
                    .computeIfAbsent(b.getCode(), k -> new ArrayList<>())
                    .add(b);
        }

        Map<String, Map<String, Bedside>> result = new HashMap<>();

        for (Map.Entry<String, Map<String, List<Bedside>>> pidEntry : grouped.entrySet()) {
            String pid = pidEntry.getKey();
            Date admissionTime = icuAdmissionTimes.get(pid);
            if (admissionTime == null) continue;

            Map<String, Bedside> codeToFirst = new HashMap<>();

            // 允许入科前1小时内的评估（整点评估场景，如15:16入科，15:00评估）
            Date adjustedAdmissionTime = new Date(admissionTime.getTime() - 60 * 60 * 1000);

            for (Map.Entry<String, List<Bedside>> codeEntry : pidEntry.getValue().entrySet()) {
                String code = codeEntry.getKey();

                // 筛选有效记录：valid=true, strVal非空, time >= 入科前1小时
                List<Bedside> validList = codeEntry.getValue().stream()
                        .filter(b -> Boolean.TRUE.equals(b.getValid()))
                        .filter(b -> b.getStrVal() != null && !b.getStrVal().trim().isEmpty())
                        .filter(b -> b.getTime() != null && !b.getTime().before(adjustedAdmissionTime))
                        .collect(Collectors.toList());

                if (validList.isEmpty()) continue;

                // 排序：time升序 → editTime升序 → _id升序 → 取第一条
                validList.sort(Comparator
                        .comparing((Bedside b) -> b.getTime(), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing((Bedside b) -> b.getEditTime(), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing((Bedside b) -> b.getId(), Comparator.nullsLast(Comparator.naturalOrder())));

                codeToFirst.put(code, validList.get(0));
            }

            if (!codeToFirst.isEmpty()) {
                result.put(pid, codeToFirst);
            }
        }
        return result;
    }

    /**
     * 从批量查询结果中按 pid 选择入科后第一次有效 score。
     *
     * @param scores 该批次所有相关 score 记录
     * @param icuAdmissionTimes pid → icuAdmissionTime 映射
     * @return pid → 第一次有效 score
     */
    public Map<String, Score> selectFirstScorePerPid(
            List<Score> scores,
            Map<String, Date> icuAdmissionTimes) {

        // 按 pid 分组
        Map<String, List<Score>> byPid = new HashMap<>();
        for (Score s : scores) {
            if (s.getPid() == null) continue;
            byPid.computeIfAbsent(s.getPid(), k -> new ArrayList<>()).add(s);
        }

        Map<String, Score> result = new HashMap<>();

        for (Map.Entry<String, List<Score>> entry : byPid.entrySet()) {
            String pid = entry.getKey();
            Date admissionTime = icuAdmissionTimes.get(pid);
            if (admissionTime == null) continue;

            // 允许入科前1小时内的评估（整点评估场景）
            Date adjustedAdmissionTime = new Date(admissionTime.getTime() - 60 * 60 * 1000);

            // 筛选有效记录：valid=true, time >= 入科前1小时
            List<Score> validList = entry.getValue().stream()
                    .filter(s -> Boolean.TRUE.equals(s.getValid()))
                    .filter(s -> s.getTime() != null && !s.getTime().before(adjustedAdmissionTime))
                    .collect(Collectors.toList());

            if (validList.isEmpty()) continue;

            // 排序：time升序 → editTime升序 → _id升序 → 取第一条
            validList.sort(Comparator
                    .comparing((Score s) -> s.getTime(), Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing((Score s) -> s.getEditTime(), Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing((Score s) -> s.getId(), Comparator.nullsLast(Comparator.naturalOrder())));

            result.put(pid, validList.get(0));
        }
        return result;
    }

    /**
     * 根据 bedside 和 score 构建候选值映射（field → value）。
     *
     * @param pid        患者 ID
     * @param bedsideMap pid → code → 第一次有效 bedside
     * @param scoreMap   pid → 第一次有效 score
     * @param formCode   当前处理的表单编码（用于按 formCode 查找字段/选项编码）
     * @return 目标字段 → 候选值
     */
    public Map<String, Object> buildCandidateValues(String pid,
                                                     Map<String, Map<String, Bedside>> bedsideMap,
                                                     Map<String, Score> scoreMap,
                                                     String formCode) {
        log.info("[FirstAssessmentSync] buildCandidateValues pid={}, formCode={}", pid, formCode);
        Map<String, Object> candidates = new LinkedHashMap<>();

        // 获取当前 formCode 的选项配置
        FormOptionConfig optionConfig = getFormOptionConfig(formCode);

        // 1a. bedside 映射：SCORE_FIELD_MAPPING（只取数值分数，不含括号结论）
        Map<String, Bedside> pidBedside = bedsideMap.getOrDefault(pid, Collections.emptyMap());
        log.info("[FirstAssessmentSync] pid={} bedside codes: {}", pid, pidBedside.keySet());
        for (Map.Entry<String, String> entry : SCORE_FIELD_MAPPING.entrySet()) {
            Bedside source = pidBedside.get(entry.getKey());
            if (source == null) continue;

            String score = extractScoreOnly(source.getStrVal());
            if (score != null && !score.isEmpty()) {
                candidates.put(entry.getValue(), score);
            }

            // param_yaChuang_score 特殊处理：截取括号内容赋值给 branden2
            if ("param_yaChuang_score".equals(entry.getKey())) {
                Optional<String> conclusion = extractParenthesizedConclusion(source.getStrVal());
                if (conclusion.isPresent()) {
                    candidates.put("branden2", conclusion.get());
                }
            }

            // param_score_unPlannedCGZYY 特殊处理：截取括号内容赋值给 dght2
            if ("param_score_unPlannedCGZYY".equals(entry.getKey())) {
                Optional<String> conclusion = extractParenthesizedConclusion(source.getStrVal());
                if (conclusion.isPresent()) {
                    candidates.put("dght2", conclusion.get());
                }
            }
        }

        // 1b. bedside 映射：BEDSIDE_CODE_MAPPING（ttpf 等，保留原有逻辑）
        for (Map.Entry<String, String[]> mapping : BEDSIDE_CODE_MAPPING.entrySet()) {
            Bedside source = pidBedside.get(mapping.getKey());
            if (source == null) continue;

            String[] targetFields = mapping.getValue();
            String strVal = source.getStrVal().trim();

            candidates.put(targetFields[0], strVal);

            if (targetFields.length > 1) {
                Optional<String> conclusion = extractParenthesizedConclusion(strVal);
                if (conclusion.isPresent()) {
                    candidates.put(targetFields[1], conclusion.get());
                }
            }
        }

        // 1c. 血压：两条 bedside 配对合成单字段 xy
        putBloodPressure(candidates, pid, pidBedside);

        // 1c2. 其他生命体征：tw、mb(优先脉搏)、hx
        resolveVitalSigns(pidBedside, candidates);

        // 1d. 意识状态：param_Yishi → yszt1(编码) + yszt8(不匹配时的原始值)
        resolveConsciousness(pidBedside, candidates);

        // 1e. 生活自理能力：根据依赖程度写入选项编码（List<String>）
        Bedside adlSource = pidBedside.get("param_score_adl");
        if (adlSource != null && optionConfig != null) {
            resolveAndPutDependencyOption(formCode, adlSource.getStrVal(), candidates);
        }

        // 2. score 映射（跌倒/坠床）
        Score score = scoreMap.get(pid);
        if (score != null) {
            boolean isMorse = isMorseUsed(score);

            // morde2: 风险结论（无论哪种方法都要赋值）
            if (score.getConclusion() != null && !score.getConclusion().trim().isEmpty()) {
                candidates.put("morde2", score.getConclusion().trim());
            }

            // morde: 只有使用 Morse 评分法时才赋值分数
            if (isMorse && score.getTotal() != null) {
                candidates.put("morde", normalizeScoreTotal(score.getTotal()));
            }

            // 跌倒评估方法：收集所有适用方法的 option value
            if (optionConfig != null) {
                collectFallMethodOptions(formCode, score, candidates);
            }
        }

        log.info("[FirstAssessmentSync] pid={} 最终 candidates: {}", pid, candidates.keySet());
        return candidates;
    }

    // ==================== 生命体征解析 ====================

    /**
     * 解析生命体征：tw(体温)、mb(脉搏/心率)、hx(呼吸)。
     *
     * <p>血压(xy) 已由 {@link #putBloodPressure} 单独处理。</p>
     *
     * <ul>
     *   <li>tw → param_T(体温)</li>
     *   <li>mb → 优先 param_脉搏，无值时兜底 param_HR</li>
     *   <li>hx → param_resp(呼吸)</li>
     * </ul>
     */
    private void resolveVitalSigns(Map<String, Bedside> pidBedside,
                                    Map<String, Object> candidates) {
        // 体温
        Bedside tw = pidBedside.get("param_T");
        if (tw != null && StringUtils.hasText(tw.getStrVal())) {
            candidates.put("tw", tw.getStrVal().trim());
            log.debug("[FirstAssessmentSync] tw 命中, val={}", tw.getStrVal().trim());
        } else {
            log.debug("[FirstAssessmentSync] tw 未命中, bedside keys={}", pidBedside.keySet());
        }

        // 脉搏：优先 param_脉搏，无值时兜底 param_HR
        Bedside pulse = pidBedside.get("param_脉搏");
        Bedside hr = pidBedside.get("param_HR");
        if (pulse != null && StringUtils.hasText(pulse.getStrVal())) {
            candidates.put("mb", pulse.getStrVal().trim());
            log.debug("[FirstAssessmentSync] mb 命中(脉搏), val={}", pulse.getStrVal().trim());
        } else if (hr != null && StringUtils.hasText(hr.getStrVal())) {
            candidates.put("mb", hr.getStrVal().trim());
            log.debug("[FirstAssessmentSync] mb 命中(HR兜底), val={}", hr.getStrVal().trim());
        } else {
            log.debug("[FirstAssessmentSync] mb 未命中(脉搏和HR均无数据), bedside keys={}", pidBedside.keySet());
        }

        // 呼吸
        Bedside resp = pidBedside.get("param_resp");
        if (resp != null && StringUtils.hasText(resp.getStrVal())) {
            candidates.put("hx", resp.getStrVal().trim());
            log.debug("[FirstAssessmentSync] hx 命中, val={}", resp.getStrVal().trim());
        } else {
            log.debug("[FirstAssessmentSync] hx 未命中, bedside keys={}", pidBedside.keySet());
        }
    }

    /**
     * 解析意识状态：param_Yishi → yszt1(拼音编码) + yszt8(不匹配时的原始中文)。
     *
     * <p>匹配表：清楚→qingchu, 昏睡→hunshui, 嗜睡→shishui,
     * 轻度昏迷→qingduhunmi, 中度昏迷→zhongduhunmi, 深度昏迷→shenduhunmi。</p>
     * <p>不匹配时：yszt1="qita", yszt8=原始中文值（如"谵妄"）。</p>
     */
    private void resolveConsciousness(Map<String, Bedside> pidBedside,
                                       Map<String, Object> candidates) {
        Bedside yishi = pidBedside.get("param_Yishi");
        if (yishi == null || !StringUtils.hasText(yishi.getStrVal())) {
            log.debug("[FirstAssessmentSync] 意识状态 param_Yishi 无数据, pidBedside keys={}", pidBedside.keySet());
            return;
        }

        String raw = yishi.getStrVal().trim();
        String code = CONSCIOUSNESS_MAP.get(raw);

        if (code != null) {
            candidates.put("yszt1", code);
            log.info("[FirstAssessmentSync] 意识状态: raw={}, mapped={}", raw, code);
        } else {
            candidates.put("yszt1", "qita");
            candidates.put("yszt8", raw);
            log.info("[FirstAssessmentSync] 意识状态 '{}' 未匹配已知值，yszt1=qita, yszt8={}", raw, raw);
        }
    }

    // ==================== 选项解析 ====================

    /**
     * 解析生活自理能力的依赖程度，写入配置的字段和选项编码。
     *
     * @param formCode    表单编码
     * @param strVal      bedside 原始值，如 "90（轻度依赖）"
     * @param candidates  候选值 map
     */
    private void resolveAndPutDependencyOption(String formCode, String strVal,
                                                Map<String, Object> candidates) {
        if (!StringUtils.hasText(strVal)) return;

        Optional<String> conclusion = extractParenthesizedConclusion(strVal);
        if (!conclusion.isPresent()) return;

        String chineseLabel = conclusion.get().trim();

        // 根据中文标签确定目标字段和拼音编码值
        Map.Entry<String, String> fieldAndValue = resolveDependencyFieldAndValue(chineseLabel);
        if (fieldAndValue == null) {
            log.warn("[FirstAssessmentSync] formCode={} 依赖程度 '{}' 无法映射为目标字段，跳过",
                    formCode, chineseLabel);
            return;
        }

        // 写入选中字段（List<String>格式，与 lcpdf 一致）
        candidates.put(fieldAndValue.getKey(), Collections.singletonList(fieldAndValue.getValue()));
    }

    /**
     * 根据依赖程度中文标签确定目标字段和拼音编码值。
     *
     * @param chineseLabel 中文标签，如 "无依赖"、"轻度依赖"、"中度依赖"、"重度依赖"
     * @return Map.Entry<字段名, 拼音编码>；无法解析时返回 null
     */
    private Map.Entry<String, String> resolveDependencyFieldAndValue(String chineseLabel) {
        if (chineseLabel == null) return null;

        String trimmed = chineseLabel.trim();

        if ("无依赖".equals(trimmed)) {
            return new AbstractMap.SimpleEntry<>("shzlnl1", "wuyilai");
        } else if ("轻度依赖".equals(trimmed)) {
            return new AbstractMap.SimpleEntry<>("shzlnl2", "qingduyilai");
        } else if ("中度依赖".equals(trimmed)) {
            return new AbstractMap.SimpleEntry<>("shzlnl3", "zhongduyilai");
        } else if ("重度依赖".equals(trimmed)) {
            return new AbstractMap.SimpleEntry<>("shzlnl4", "zhongduyilai");
        }

        log.warn("[FirstAssessmentSync] 无法解析依赖程度: {}", trimmed);
        return null;
    }

    /**
     * 收集跌倒评估方法的所有适用 option value，分别写入对应字段。
     *
     * @param formCode    表单编码
     * @param score       score 记录
     * @param candidates  候选值 map
     */
    private void collectFallMethodOptions(String formCode, Score score,
                                           Map<String, Object> candidates) {
        List<String> fallMethodFields = getFallMethodFields(formCode);
        if (fallMethodFields.isEmpty()) {
            log.warn("[FirstAssessmentSync] formCode={} fallMethodField 未配置，跳过跌倒评估方法同步", formCode);
            return;
        }

        // 临床判定法 → 第一个字段 (lcpdf)
        if (isClinicalJudgmentUsed(score)) {
            String clinicalValue = resolveFallMethodOptionValue(formCode, "临床判定法");
            if (StringUtils.hasText(clinicalValue)) {
                candidates.put(fallMethodFields.get(0), Collections.singletonList(clinicalValue));
            } else {
                log.warn("[FirstAssessmentSync] formCode={} 临床判定法选项编码未配置，跳过", formCode);
            }
        }

        // Morse 评分量表 → 第二个字段 (mpff)
        if (isMorseUsed(score)) {
            String morseValue = resolveFallMethodOptionValue(formCode, "Morse评分量表");
            if (StringUtils.hasText(morseValue) && fallMethodFields.size() > 1) {
                candidates.put(fallMethodFields.get(1), Collections.singletonList(morseValue));
            } else if (!StringUtils.hasText(morseValue)) {
                log.warn("[FirstAssessmentSync] formCode={} Morse评分量表选项编码未配置，跳过", formCode);
            } else {
                log.warn("[FirstAssessmentSync] formCode={} fallMethodField 只配置了1个字段，无法写入Morse评分法", formCode);
            }
        }
    }

    // ==================== 配置查询 ====================

    /**
     * 获取指定 formCode 的选项配置。
     */
    FormOptionConfig getFormOptionConfig(String formCode) {
        if (properties.getFormOptionConfigs() == null) return null;
        return properties.getFormOptionConfigs().get(formCode);
    }

    /**
     * 获取跌倒评估方法字段编码列表。
     */
    private List<String> getFallMethodFields(String formCode) {
        FormOptionConfig config = getFormOptionConfig(formCode);
        return config != null ? config.getFallMethodFieldList() : Collections.emptyList();
    }

    /**
     * 解析生活自理能力选项：中文名称 → 数据库 option value。
     */
    String resolveDependencyOptionValue(String formCode, String chineseLabel) {
        if (!StringUtils.hasText(chineseLabel)) return null;

        FormOptionConfig config = getFormOptionConfig(formCode);
        if (config == null || config.getDependencyOptions() == null) return null;

        // 中文key → 英文key映射
        String englishKey = mapChineseToEnglishKey(chineseLabel.trim(), "dependency");
        if (englishKey == null) {
            log.warn("[FirstAssessmentSync] formCode={} 依赖程度选项 '{}' 无法映射为英文key",
                    formCode, chineseLabel);
            return null;
        }

        String value = config.getDependencyOptions().get(englishKey);
        if (!StringUtils.hasText(value)) {
            log.warn("[FirstAssessmentSync] formCode={} 依赖程度选项 '{}' 无对应编码配置",
                    formCode, chineseLabel);
            return null;
        }
        return value.trim();
    }

    /**
     * 解析跌倒评估方法选项：中文名称 → 数据库 option value。
     */
    String resolveFallMethodOptionValue(String formCode, String chineseLabel) {
        if (!StringUtils.hasText(chineseLabel)) return null;

        FormOptionConfig config = getFormOptionConfig(formCode);
        if (config == null || config.getFallMethodOptions() == null) return null;

        // 中文key → 英文key映射
        String englishKey = mapChineseToEnglishKey(chineseLabel.trim(), "fallMethod");
        if (englishKey == null) {
            log.warn("[FirstAssessmentSync] formCode={} 跌倒评估方法选项 '{}' 无法映射为英文key",
                    formCode, chineseLabel);
            return null;
        }

        String value = config.getFallMethodOptions().get(englishKey);
        if (!StringUtils.hasText(value)) {
            log.warn("[FirstAssessmentSync] formCode={} 跌倒评估方法选项 '{}' 无对应编码配置",
                    formCode, chineseLabel);
            return null;
        }
        return value.trim();
    }

    /**
     * 中文key → 英文key映射。
     */
    private String mapChineseToEnglishKey(String chineseLabel, String type) {
        if ("dependency".equals(type)) {
            switch (chineseLabel) {
                case "无依赖": return "NONE";
                case "轻度依赖": return "MILD";
                case "中度依赖": return "MODERATE";
                case "重度依赖": return "SEVERE";
                default: return null;
            }
        } else if ("fallMethod".equals(type)) {
            switch (chineseLabel) {
                case "临床判定法": return "CLINICAL";
                case "Morse评分量表": return "MORSE";
                default: return null;
            }
        }
        return null;
    }

    // ==================== 内部工具 ====================

    /**
     * 从评估值中只提取数值分数，去除括号及括号中的风险等级/结论。
     * <p>支持中文括号"（）"和英文括号"()"，支持整数、小数、负数。</p>
     *
     * @param value 原始评估值，如 "15（低风险）"、"90(轻度依赖)"、"12 分（高风险）"
     * @return 纯数字分数字符串，如 "15"、"90"、"12"；无法提取时返回 null
     */
    String extractScoreOnly(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        String normalized = value.trim();

        // 去掉第一个括号及其后全部内容
        int chineseBracket = normalized.indexOf('（');
        int englishBracket = normalized.indexOf('(');

        int bracketIndex;
        if (chineseBracket >= 0 && englishBracket >= 0) {
            bracketIndex = Math.min(chineseBracket, englishBracket);
        } else if (chineseBracket >= 0) {
            bracketIndex = chineseBracket;
        } else {
            bracketIndex = englishBracket;
        }

        if (bracketIndex >= 0) {
            normalized = normalized.substring(0, bracketIndex).trim();
        }

        // 用正则提取第一个完整数字
        Matcher matcher = SCORE_PATTERN.matcher(normalized);
        if (matcher.find()) {
            return matcher.group();
        }

        log.warn("[FirstAssessmentSync] 无法从评估值中提取分数，value={}", value);
        return null;
    }

    Optional<String> extractParenthesizedConclusion(String value) {
        if (value == null) return Optional.empty();
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("[（(]\\s*(.+?)\\s*[）)]").matcher(value.trim());
        if (m.find()) {
            String inner = m.group(1).trim();
            if (!inner.isEmpty()) return Optional.of(inner);
        }
        return Optional.empty();
    }

    private String normalizeScoreTotal(Object total) {
        if (total instanceof Number) {
            int v = ((Number) total).intValue();
            return String.valueOf(v);
        }
        String s = total.toString().trim();
        try {
            int v = (int) Double.parseDouble(s);
            return String.valueOf(v);
        } catch (NumberFormatException e) {
            return s;
        }
    }

    /**
     * 临床判定法：任意关键字段为 Boolean.TRUE 或字符串 "true" 即为 true。
     */
    private boolean isClinicalJudgmentUsed(Score score) {
        Map<String, Object> factor = score.getPatientFallDangerFactorV2();
        if (factor == null) return false;
        String[] keys = {"hunmiOntanhaun", "preHospitalization", "sylzys",
                "age", "thisHospitalization", "exist", "sixHours"};
        for (String key : keys) {
            if (isStrictTrue(factor.get(key))) return true;
        }
        return false;
    }

    private boolean isStrictTrue(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean) return Boolean.TRUE.equals(value);
        if (value instanceof String) {
            return "true".equalsIgnoreCase(((String) value).trim());
        }
        return false;
    }

    /**
     * Morse 评分量表：任意关键字段能安全转换为数字即为 true（包括 0）。
     */
    private boolean isMorseUsed(Score score) {
        Map<String, Object> factor = score.getPatientFallDangerFactorV2();
        if (factor == null) return false;
        String[] keys = {"fallHistory", "otherDiagnosis", "useWalkTool",
                "intravenousInjection", "walk", "mentality"};
        for (String key : keys) {
            if (isNumericValue(factor.get(key))) return true;
        }
        return false;
    }

    private boolean isNumericValue(Object value) {
        if (value == null) return false;
        if (value instanceof Number) return true;
        if (value instanceof String) {
            String s = ((String) value).trim();
            if (s.isEmpty()) return false;
            try {
                Double.parseDouble(s);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════
    //  血压合成
    // ══════════════════════════════════════════════════════════════

    /**
     * 血压合成：param_nibp_s（收缩压）与 param_nibp_d（舒张压）
     * 两条 bedside 配对，合成 "收缩压/舒张压" 写入单字段 xy。
     *
     * <p>规则：</p>
     * <ul>
     *   <li>两条必须都有有效首条数据才写入，缺任意一条整体跳过，
     *       避免产生 "120/" 或 "/80" 这类半截值</li>
     *   <li>各自独立取「入科后第一条有效数据」，不要求两者时间戳完全相同
     *       （监护仪采集一般同时间，若不同仅告警不阻断）</li>
     *   <li>值先做数字提取规范化，兼容 "120mmHg" 这类带单位写法</li>
     * </ul>
     *
     * @param candidates  候选值容器，直接写入
     * @param pid         患者 ID，仅用于日志
     * @param pidBedside  该患者 code → 首条有效 bedside
     */
    private void putBloodPressure(Map<String, Object> candidates,
                                  String pid,
                                  Map<String, Bedside> pidBedside) {
        Bedside sysBed = pidBedside.get(CODE_NIBP_S);
        Bedside diaBed = pidBedside.get(CODE_NIBP_D);

        String sys = normalizeVital(sysBed);
        String dia = normalizeVital(diaBed);

        if (sys == null || dia == null) {
            log.info("[FirstAssessmentSync] pid={} 血压跳过: {}={}, {}={}",
                    pid, CODE_NIBP_S, sys, CODE_NIBP_D, dia);
            return;
        }

        if (sysBed.getTime() != null && diaBed.getTime() != null
                && !sysBed.getTime().equals(diaBed.getTime())) {
            log.warn("[FirstAssessmentSync] pid={} 血压首条时间不一致: 收缩压={}, 舒张压={}，仍按各自首条合成",
                    pid, sysBed.getTime(), diaBed.getTime());
        }

        String value = sys + BP_SEPARATOR + dia;
        candidates.put(FIELD_XY, value);
        log.info("[FirstAssessmentSync] pid={} 血压 xy={} (time={})",
                pid, value, sysBed.getTime());
    }

    /**
     * 生命体征值规范化：优先提取第一个数字，提取不到则返回 trim 后的原值。
     *
     * <p>例："120" → "120"；"120mmHg" → "120"；"36.5℃" → "36.5"。</p>
     *
     * @return 规范化后的值；源为 null 或 strVal 为空时返回 null
     */
    private String normalizeVital(Bedside source) {
        if (source == null || !StringUtils.hasText(source.getStrVal())) {
            return null;
        }
        String raw = source.getStrVal().trim();
        Matcher m = SCORE_PATTERN.matcher(raw);
        if (m.find()) {
            return m.group();
        }
        return raw;
    }
}
