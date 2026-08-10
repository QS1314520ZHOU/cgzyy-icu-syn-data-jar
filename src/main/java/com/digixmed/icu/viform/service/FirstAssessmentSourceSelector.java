package com.digixmed.icu.viform.service;

import com.digixmed.icu.viform.config.FirstAdmissionAssessmentSyncProperties;
import com.digixmed.icu.viform.entity.Bedside;
import com.digixmed.icu.viform.entity.Score;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 从 bedside/score 中选择入科后第一次有效评估的逻辑。
 *
 * <p>按 (pid, code) 分组后取 time 升序第一条。</p>
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
        SCORE_FIELD_MAPPING.put("param_score_dght",     "dght");     // 管道滑脱评估
    }

    /** 匹配数字（整数、小数、负数） */
    private static final Pattern SCORE_PATTERN = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");

    // ── 其他 bedside code → 表单字段 ────────────────────────────────

    private static final Map<String, String[]> BEDSIDE_CODE_MAPPING = new LinkedHashMap<>();
    static {
        BEDSIDE_CODE_MAPPING.put("param_tengTong_score", new String[]{"ttpf"});
    }

    /** mpff 固定值（Morse 评分方法） */
    private static final String MORSE_METHOD_VALUE = "Mordepingfenfa";

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

            for (Map.Entry<String, List<Bedside>> codeEntry : pidEntry.getValue().entrySet()) {
                String code = codeEntry.getKey();

                // 筛选有效记录：valid=true, strVal非空, time >= icuAdmissionTime
                List<Bedside> validList = codeEntry.getValue().stream()
                        .filter(b -> Boolean.TRUE.equals(b.getValid()))
                        .filter(b -> b.getStrVal() != null && !b.getStrVal().trim().isEmpty())
                        .filter(b -> b.getTime() != null && !b.getTime().before(admissionTime))
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

            // 筛选有效记录：valid=true, time >= icuAdmissionTime
            List<Score> validList = entry.getValue().stream()
                    .filter(s -> Boolean.TRUE.equals(s.getValid()))
                    .filter(s -> s.getTime() != null && !s.getTime().before(admissionTime))
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
     * @param formCode   当前处理的表单编码（用于 lcpdf 编码查找）
     * @return 目标字段 → 候选值
     */
    public Map<String, Object> buildCandidateValues(String pid,
                                                     Map<String, Map<String, Bedside>> bedsideMap,
                                                     Map<String, Score> scoreMap,
                                                     String formCode) {
        Map<String, Object> candidates = new LinkedHashMap<>();

        // 1a. bedside 映射：SCORE_FIELD_MAPPING（只取数值分数，不含括号结论）
        Map<String, Bedside> pidBedside = bedsideMap.getOrDefault(pid, Collections.emptyMap());
        for (Map.Entry<String, String> entry : SCORE_FIELD_MAPPING.entrySet()) {
            Bedside source = pidBedside.get(entry.getKey());
            if (source == null) continue;

            String score = extractScoreOnly(source.getStrVal());
            if (score != null && !score.isEmpty()) {
                candidates.put(entry.getValue(), score);
            }

            // shzlnl 特殊处理：解析依赖程度（shzlnl1-4）
            if ("shzlnl".equals(entry.getValue())) {
                Optional<String> conclusion = extractParenthesizedConclusion(source.getStrVal());
                if (conclusion.isPresent()) {
                    String dependency = conclusion.get();
                    List<String> dependencyFields = resolveDependencyFields(dependency);
                    if (dependencyFields != null) {
                        candidates.put(entry.getValue() + "1", dependencyFields);
                    }
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

        // 2. score 映射（跌倒/坠床）
        Score score = scoreMap.get(pid);
        if (score != null) {
            // morde: score.total → 规范化
            if (score.getTotal() != null) {
                candidates.put("morde", normalizeScoreTotal(score.getTotal()));
            }
            // morde2: score.conclusion
            if (score.getConclusion() != null && !score.getConclusion().trim().isEmpty()) {
                candidates.put("morde2", score.getConclusion().trim());
            }

            // 临床判定法 → lcpdf (List<String>)，按 formCode 分别配置
            boolean clinicalUsed = isClinicalJudgmentUsed(score);
            if (clinicalUsed) {
                String clinicalValue = resolveClinicalMethodValue(formCode);
                if (clinicalValue == null) {
                    log.warn("[FirstAssessmentSync] clinical-method-values 未配置 formCode={}，跳过 lcpdf", formCode);
                } else {
                    candidates.put("lcpdf", Collections.singletonList(clinicalValue));
                }
            }

            // Morse 评分量表 → mpff (List<String>)
            boolean morseUsed = isMorseUsed(score);
            if (morseUsed) {
                candidates.put("mpff", Collections.singletonList(MORSE_METHOD_VALUE));
            }
        }

        return candidates;
    }

    // ==================== 内部工具 ====================

    /**
     * 按 formCode 解析临床判定法选项编码。
     * <p>优先查 Map；fallback 查旧字段 clinicalMethodValue。</p>
     */
    private String resolveClinicalMethodValue(String formCode) {
        Map<String, String> values = properties.getClinicalMethodValues();
        if (values != null && values.containsKey(formCode)) {
            String v = values.get(formCode);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        // fallback: 旧配置
        String fallback = properties.getClinicalMethodValue();
        if (fallback != null && !fallback.trim().isEmpty()) {
            return fallback.trim();
        }
        return null;
    }

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
        int chineseBracket = normalized.indexOf('（'); // （
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

    private Optional<String> extractParenthesizedConclusion(String value) {
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

    /**
     * 解析依赖程度对应的字段。
     * <p>根据依赖程度文本返回对应的字段名列表。</p>
     *
     * @param dependency 依赖程度文本，如 "无依赖"、"轻度依赖"、"中度依赖"、"重度依赖"
     * @return 对应的字段名列表，如 ["shzlnl2"]；无法解析时返回 null
     */
    private List<String> resolveDependencyFields(String dependency) {
        if (dependency == null) return null;

        String trimmed = dependency.trim();

        // 根据依赖程度返回对应的字段名
        if ("无依赖".equals(trimmed)) {
            return Collections.singletonList("shzlnl1");
        } else if ("轻度依赖".equals(trimmed)) {
            return Collections.singletonList("shzlnl2");
        } else if ("中度依赖".equals(trimmed)) {
            return Collections.singletonList("shzlnl3");
        } else if ("重度依赖".equals(trimmed)) {
            return Collections.singletonList("shzlnl4");
        }

        log.warn("[FirstAssessmentSync] 无法解析依赖程度: {}", trimmed);
        return null;
    }
}
