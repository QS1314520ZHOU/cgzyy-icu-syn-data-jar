package com.digixmed.icu.viform.service;

import com.digixmed.icu.viform.config.FirstAdmissionAssessmentSyncProperties;
import com.digixmed.icu.viform.config.FirstAdmissionAssessmentSyncProperties.FormOptionConfig;
import com.digixmed.icu.viform.entity.Bedside;
import com.digixmed.icu.viform.entity.Score;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.LocalDateTime;
import java.util.*;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class FirstAssessmentSourceSelectorTest {

    private FirstAssessmentSourceSelector selector;
    private FirstAdmissionAssessmentSyncProperties props;

    /** 模拟 formCode → 选项编码配置 */
    private static final String FORM_CODE = "zhuanruhulipinggudan";

    /** 模拟的数据库选项编码（非中文、非拼音猜测，仅为测试用） */
    private static final String DEP_FIELD = "shzlnlChecked";
    private static final String DEP_OPT_NONE = "opt_dep_001";
    private static final String DEP_OPT_MILD = "opt_dep_002";
    private static final String DEP_OPT_MODERATE = "opt_dep_003";
    private static final String DEP_OPT_SEVERE = "opt_dep_004";
    private static final String FALL_FIELD_1 = "lcpdf";
    private static final String FALL_FIELD_2 = "mpff";
    private static final String FALL_OPT_CLINICAL = "opt_fall_clinical";
    private static final String FALL_OPT_MORSE = "opt_fall_morse";

    @BeforeEach
    void setUp() {
        props = new FirstAdmissionAssessmentSyncProperties();

        // 配置 formOptionConfig（key 使用英文，与 mapChineseToEnglishKey 匹配）
        FormOptionConfig config = new FormOptionConfig();
        config.setDependencyField(DEP_FIELD);
        config.setDependencyOptions(new LinkedHashMap<String, String>() {{
            put("NONE", DEP_OPT_NONE);
            put("MILD", DEP_OPT_MILD);
            put("MODERATE", DEP_OPT_MODERATE);
            put("SEVERE", DEP_OPT_SEVERE);
        }});
        config.setFallMethodField(FALL_FIELD_1 + "," + FALL_FIELD_2);
        config.setFallMethodOptions(new LinkedHashMap<String, String>() {{
            put("CLINICAL", FALL_OPT_CLINICAL);
            put("MORSE", FALL_OPT_MORSE);
        }});

        props.setFormOptionConfigs(new LinkedHashMap<>());
        props.getFormOptionConfigs().put(FORM_CODE, config);

        selector = new FirstAssessmentSourceSelector(props);
    }

    // ══════════════════════════════════════════════════════════════════
    // selectFirstBedsidePerPidAndCode
    // ══════════════════════════════════════════════════════════════════

    @Test
    void firstBedside_earliestAfterAdmission() {
        Date admissionTime = parseDate("2026-08-01T10:00:00");
        List<Bedside> bedsides = Arrays.asList(
                buildBedside("p1", "param_tengTong_score", "5",
                        parseDate("2026-08-01T12:00:00"), parseDate("2026-08-01T12:30:00"), "id1"),
                buildBedside("p1", "param_tengTong_score", "3",
                        parseDate("2026-08-01T11:00:00"), parseDate("2026-08-01T11:30:00"), "id2")
        );
        Map<String, Date> admissionTimes = Map.of("p1", admissionTime);
        Map<String, Map<String, Bedside>> result =
                selector.selectFirstBedsidePerPidAndCode(bedsides, admissionTimes);
        assertTrue(result.containsKey("p1"));
        assertEquals("3", result.get("p1").get("param_tengTong_score").getStrVal());
    }

    @Test
    void firstBedside_beforeAdmissionExcluded() {
        Date admissionTime = parseDate("2026-08-01T10:00:00");
        List<Bedside> bedsides = Arrays.asList(
                buildBedside("p1", "param_tengTong_score", "5",
                        parseDate("2026-08-01T08:00:00"), new Date(), "id1")
        );
        Map<String, Date> admissionTimes = Map.of("p1", admissionTime);
        Map<String, Map<String, Bedside>> result =
                selector.selectFirstBedsidePerPidAndCode(bedsides, admissionTimes);
        assertFalse(result.containsKey("p1"));
    }

    @Test
    void firstBedside_invalidExcluded() {
        Date admissionTime = parseDate("2026-08-01T10:00:00");
        Bedside b = buildBedside("p1", "param_tengTong_score", "5",
                parseDate("2026-08-01T12:00:00"), new Date(), "id1");
        b.setValid(false);
        Map<String, Date> admissionTimes = Map.of("p1", admissionTime);
        Map<String, Map<String, Bedside>> result =
                selector.selectFirstBedsidePerPidAndCode(Arrays.asList(b), admissionTimes);
        assertFalse(result.containsKey("p1"));
    }

    @Test
    void firstBedside_emptyStrValExcluded() {
        Date admissionTime = parseDate("2026-08-01T10:00:00");
        List<Bedside> bedsides = Arrays.asList(
                buildBedside("p1", "param_tengTong_score", "  ",
                        parseDate("2026-08-01T12:00:00"), new Date(), "id1")
        );
        Map<String, Date> admissionTimes = Map.of("p1", admissionTime);
        Map<String, Map<String, Bedside>> result =
                selector.selectFirstBedsidePerPidAndCode(bedsides, admissionTimes);
        assertFalse(result.containsKey("p1"));
    }

    @Test
    void firstBedside_differentPatientsNotMixed() {
        Date admissionTime = parseDate("2026-08-01T10:00:00");
        List<Bedside> bedsides = Arrays.asList(
                buildBedside("p1", "param_tengTong_score", "3",
                        parseDate("2026-08-01T11:00:00"), new Date(), "id1"),
                buildBedside("p2", "param_tengTong_score", "7",
                        parseDate("2026-08-01T11:00:00"), new Date(), "id2")
        );
        Map<String, Date> admissionTimes = Map.of("p1", admissionTime, "p2", admissionTime);
        Map<String, Map<String, Bedside>> result =
                selector.selectFirstBedsidePerPidAndCode(bedsides, admissionTimes);
        assertEquals("3", result.get("p1").get("param_tengTong_score").getStrVal());
        assertEquals("7", result.get("p2").get("param_tengTong_score").getStrVal());
    }

    @Test
    void firstBedside_sameTimeSortByEditTime() {
        Date admissionTime = parseDate("2026-08-01T10:00:00");
        Date sameTime = parseDate("2026-08-01T12:00:00");
        List<Bedside> bedsides = Arrays.asList(
                buildBedside("p1", "param_tengTong_score", "5", sameTime,
                        parseDate("2026-08-01T12:30:00"), "id2"),
                buildBedside("p1", "param_tengTong_score", "3", sameTime,
                        parseDate("2026-08-01T12:15:00"), "id1")
        );
        Map<String, Date> admissionTimes = Map.of("p1", admissionTime);
        Map<String, Map<String, Bedside>> result =
                selector.selectFirstBedsidePerPidAndCode(bedsides, admissionTimes);
        assertEquals("3", result.get("p1").get("param_tengTong_score").getStrVal());
    }

    // ══════════════════════════════════════════════════════════════════
    // selectFirstScorePerPid
    // ══════════════════════════════════════════════════════════════════

    @Test
    void firstScore_earliestAfterAdmission() {
        Date admissionTime = parseDate("2026-08-01T10:00:00");
        List<Score> scores = Arrays.asList(
                buildScore("p1", parseDate("2026-08-01T12:00:00"), "s1"),
                buildScore("p1", parseDate("2026-08-01T11:00:00"), "s2")
        );
        Map<String, Date> admissionTimes = Map.of("p1", admissionTime);
        Map<String, Score> result = selector.selectFirstScorePerPid(scores, admissionTimes);
        assertEquals("s2", result.get("p1").getId());
    }

    // ══════════════════════════════════════════════════════════════════
    // extractScoreOnly
    // ══════════════════════════════════════════════════════════════════

    @Test
    void extractScoreOnly_chineseBracket() {
        assertEquals("15", selector.extractScoreOnly("15（低风险）"));
    }

    @Test
    void extractScoreOnly_englishBracket() {
        assertEquals("15", selector.extractScoreOnly("15(低风险)"));
    }

    @Test
    void extractScoreOnly_barthel() {
        assertEquals("90", selector.extractScoreOnly("90（轻度依赖）"));
    }

    @Test
    void extractScoreOnly_withSpaceAndUnit() {
        assertEquals("12", selector.extractScoreOnly("12 分（高风险）"));
    }

    @Test
    void extractScoreOnly_withUnitNoBracket() {
        assertEquals("18", selector.extractScoreOnly("18分"));
    }

    @Test
    void extractScoreOnly_leadingTrailingSpaces() {
        assertEquals("20", selector.extractScoreOnly(" 20 （无风险） "));
    }

    @Test
    void extractScoreOnly_decimal() {
        assertEquals("7.5", selector.extractScoreOnly("7.5（风险）"));
    }

    @Test
    void extractScoreOnly_emptyString() {
        assertNull(selector.extractScoreOnly(""));
    }

    @Test
    void extractScoreOnly_null() {
        assertNull(selector.extractScoreOnly(null));
    }

    @Test
    void extractScoreOnly_noDigit() {
        assertNull(selector.extractScoreOnly("低风险"));
    }

    @Test
    void extractScoreOnly_onlyBracketContent() {
        assertNull(selector.extractScoreOnly("（低风险）"));
    }

    @Test
    void extractScoreOnly_negativeNumber() {
        assertEquals("-3", selector.extractScoreOnly("-3（测试）"));
    }

    // ══════════════════════════════════════════════════════════════════
    // resolveDependencyOptionValue
    // ══════════════════════════════════════════════════════════════════

    @Test
    void resolveDependency_none() {
        assertEquals(DEP_OPT_NONE, selector.resolveDependencyOptionValue(FORM_CODE, "无依赖"));
    }

    @Test
    void resolveDependency_mild() {
        assertEquals(DEP_OPT_MILD, selector.resolveDependencyOptionValue(FORM_CODE, "轻度依赖"));
    }

    @Test
    void resolveDependency_moderate() {
        assertEquals(DEP_OPT_MODERATE, selector.resolveDependencyOptionValue(FORM_CODE, "中度依赖"));
    }

    @Test
    void resolveDependency_severe() {
        assertEquals(DEP_OPT_SEVERE, selector.resolveDependencyOptionValue(FORM_CODE, "重度依赖"));
    }

    @Test
    void resolveDependency_unknown() {
        assertNull(selector.resolveDependencyOptionValue(FORM_CODE, "未知"));
    }

    @Test
    void resolveDependency_nullFormConfig() {
        assertNull(selector.resolveDependencyOptionValue("nonexistent_form", "无依赖"));
    }

    // ══════════════════════════════════════════════════════════════════
    // resolveFallMethodOptionValue
    // ══════════════════════════════════════════════════════════════════

    @Test
    void resolveFallMethod_clinical() {
        assertEquals(FALL_OPT_CLINICAL, selector.resolveFallMethodOptionValue(FORM_CODE, "临床判定法"));
    }

    @Test
    void resolveFallMethod_morse() {
        assertEquals(FALL_OPT_MORSE, selector.resolveFallMethodOptionValue(FORM_CODE, "Morse评分量表"));
    }

    @Test
    void resolveFallMethod_unknown() {
        assertNull(selector.resolveFallMethodOptionValue(FORM_CODE, "未知方法"));
    }

    // ══════════════════════════════════════════════════════════════════
    // buildCandidateValues
    // ══════════════════════════════════════════════════════════════════

    @Test
    void candidate_ttpfMapping() {
        Bedside teng = buildBedside("p1", "param_tengTong_score", "3",
                parseDate("2026-08-01T11:00:00"), new Date(), "id1");
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", Map.of("param_tengTong_score", teng));
        Map<String, Object> result = selector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap(), FORM_CODE);
        assertEquals("3", result.get("ttpf"));
    }

    @Test
    void candidate_bradenScoreOnly() {
        Bedside braden = buildBedside("p1", "param_yaChuang_score", "12(高度危险)",
                parseDate("2026-08-01T11:00:00"), new Date(), "id1");
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", Map.of("param_yaChuang_score", braden));
        Map<String, Object> result = selector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap(), FORM_CODE);
        assertEquals("12", result.get("braden"));
    }

    @Test
    void candidate_dependencyNone() {
        Bedside adl = buildBedside("p1", "param_score_adl", "90（无依赖）",
                parseDate("2026-08-01T11:00:00"), new Date(), "id1");
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", Map.of("param_score_adl", adl));
        Map<String, Object> result = selector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap(), FORM_CODE);
        assertEquals("90", result.get("shzlnl"));
        // 硬编码逻辑：无依赖 → shzlnl1 = ["wuyilai"]
        assertEquals(Collections.singletonList("wuyilai"), result.get("shzlnl1"));
    }

    @Test
    void candidate_dependencyMild() {
        Bedside adl = buildBedside("p1", "param_score_adl", "90（轻度依赖）",
                parseDate("2026-08-01T11:00:00"), new Date(), "id1");
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", Map.of("param_score_adl", adl));
        Map<String, Object> result = selector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap(), FORM_CODE);
        assertEquals("90", result.get("shzlnl"));
        // 硬编码逻辑：轻度依赖 → shzlnl2 = ["qingduyilai"]
        assertEquals(Collections.singletonList("qingduyilai"), result.get("shzlnl2"));
    }

    @Test
    void candidate_dependencyModerate() {
        Bedside adl = buildBedside("p1", "param_score_adl", "60（中度依赖）",
                parseDate("2026-08-01T11:00:00"), new Date(), "id1");
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", Map.of("param_score_adl", adl));
        Map<String, Object> result = selector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap(), FORM_CODE);
        assertEquals("60", result.get("shzlnl"));
        // 硬编码逻辑：中度依赖 → shzlnl3 = ["zhongduyilai"]
        assertEquals(Collections.singletonList("zhongduyilai"), result.get("shzlnl3"));
    }

    @Test
    void candidate_dependencySevere() {
        Bedside adl = buildBedside("p1", "param_score_adl", "20（重度依赖）",
                parseDate("2026-08-01T11:00:00"), new Date(), "id1");
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", Map.of("param_score_adl", adl));
        Map<String, Object> result = selector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap(), FORM_CODE);
        assertEquals("20", result.get("shzlnl"));
        // 硬编码逻辑：重度依赖 → shzlnl4 = ["zhongduyilai"]
        assertEquals(Collections.singletonList("zhongduyilai"), result.get("shzlnl4"));
    }

    @Test
    void candidate_dghtScoreOnly() {
        Bedside dght = buildBedside("p1", "param_score_unPlannedCGZYY", "8（高风险）",
                parseDate("2026-08-01T11:00:00"), new Date(), "id1");
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", Map.of("param_score_unPlannedCGZYY", dght));
        Map<String, Object> result = selector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap(), FORM_CODE);
        assertEquals("8", result.get("dght"));
    }

    @Test
    void candidate_scoreMorseTotal() {
        Score score = new Score();
        score.setPid("p1");
        score.setTotal(35);
        score.setConclusion("低风险");
        Map<String, Score> scoreMap = Map.of("p1", score);
        Map<String, Object> result = selector.buildCandidateValues("p1", Collections.emptyMap(), scoreMap, FORM_CODE);
        assertEquals("35", result.get("morde"));
        assertEquals("低风险", result.get("morde2"));
    }

    // ── 跌倒评估方法（合并到同一字段） ──────────────────────────────

    @Test
    void candidate_fallMethodClinicalOnly() {
        Score score = new Score();
        score.setPid("p1");
        score.setPatientFallDangerFactorV2(new HashMap<String, Object>() {{
            put("age", true);
        }});
        Map<String, Score> scoreMap = Map.of("p1", score);
        Map<String, Object> result = selector.buildCandidateValues("p1", Collections.emptyMap(), scoreMap, FORM_CODE);
        assertEquals(Collections.singletonList(FALL_OPT_CLINICAL), result.get(FALL_FIELD_1));
    }

    @Test
    void candidate_fallMethodMorseOnly() {
        Score score = new Score();
        score.setPid("p1");
        score.setPatientFallDangerFactorV2(new HashMap<String, Object>() {{
            put("fallHistory", 15);
        }});
        Map<String, Score> scoreMap = Map.of("p1", score);
        Map<String, Object> result = selector.buildCandidateValues("p1", Collections.emptyMap(), scoreMap, FORM_CODE);
        // Morse 写入第二个字段 (mpff)
        assertEquals(Collections.singletonList(FALL_OPT_MORSE), result.get(FALL_FIELD_2));
    }

    @Test
    void candidate_fallMethodBoth() {
        Score score = new Score();
        score.setPid("p1");
        score.setPatientFallDangerFactorV2(new HashMap<String, Object>() {{
            put("age", true);
            put("fallHistory", 15);
        }});
        Map<String, Score> scoreMap = Map.of("p1", score);
        Map<String, Object> result = selector.buildCandidateValues("p1", Collections.emptyMap(), scoreMap, FORM_CODE);
        // 临床判定法 → lcpdf, Morse → mpff
        assertEquals(Collections.singletonList(FALL_OPT_CLINICAL), result.get(FALL_FIELD_1));
        assertEquals(Collections.singletonList(FALL_OPT_MORSE), result.get(FALL_FIELD_2));
    }

    @Test
    void candidate_fallMethodNotConfigured_skipped() {
        FirstAdmissionAssessmentSyncProperties noFallProps = new FirstAdmissionAssessmentSyncProperties();
        FormOptionConfig config = new FormOptionConfig();
        config.setDependencyField(DEP_FIELD);
        config.setDependencyOptions(new LinkedHashMap<String, String>() {{
            put("NONE", DEP_OPT_NONE);
        }});
        // fallMethodField 未配置
        noFallProps.setFormOptionConfigs(Map.of(FORM_CODE, config));
        FirstAssessmentSourceSelector noFallSelector = new FirstAssessmentSourceSelector(noFallProps);

        Score score = new Score();
        score.setPid("p1");
        score.setPatientFallDangerFactorV2(new HashMap<String, Object>() {{
            put("age", true);
        }});
        Map<String, Score> scoreMap = Map.of("p1", score);
        Map<String, Object> result = noFallSelector.buildCandidateValues("p1", Collections.emptyMap(), scoreMap, FORM_CODE);
        assertNull(result.get(FALL_FIELD_1));
    }

    @Test
    void candidate_dependencyNotConfigured_skipped() {
        FirstAdmissionAssessmentSyncProperties noDepProps = new FirstAdmissionAssessmentSyncProperties();
        FormOptionConfig config = new FormOptionConfig();
        config.setFallMethodField(FALL_FIELD_1);
        config.setFallMethodOptions(new LinkedHashMap<String, String>() {{
            put("CLINICAL", FALL_OPT_CLINICAL);
        }});
        // dependencyField 未配置
        noDepProps.setFormOptionConfigs(new LinkedHashMap<>());
        noDepProps.getFormOptionConfigs().put(FORM_CODE, config);
        FirstAssessmentSourceSelector noDepSelector = new FirstAssessmentSourceSelector(noDepProps);

        Bedside adl = buildBedside("p1", "param_score_adl", "90（轻度依赖）",
                parseDate("2026-08-01T11:00:00"), new Date(), "id1");
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", Map.of("param_score_adl", adl));
        Map<String, Object> result = noDepSelector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap(), FORM_CODE);
        // 分数仍然写入 shzlnl
        assertEquals("90", result.get("shzlnl"));
        // 但选项字段未配置，不写入选项编码
        assertNull(result.get(DEP_FIELD));
    }

    @Test
    void candidate_dependencyEnglishBracket() {
        Bedside adl = buildBedside("p1", "param_score_adl", "90(轻度依赖)",
                parseDate("2026-08-01T11:00:00"), new Date(), "id1");
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", Map.of("param_score_adl", adl));
        Map<String, Object> result = selector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap(), FORM_CODE);
        // 硬编码逻辑：轻度依赖 → shzlnl2 = ["qingduyilai"]
        assertEquals(Collections.singletonList("qingduyilai"), result.get("shzlnl2"));
    }

    // ── 两个 formCode 不同编码 ────────────────────────────────────────

    @Test
    void candidate_differentFormCodes_differentValues() {
        // 配置第二个 formCode，使用不同编码
        FormOptionConfig config2 = new FormOptionConfig();
        config2.setDependencyField("depField2");
        config2.setDependencyOptions(new LinkedHashMap<String, String>() {{
            put("NONE", "dep2_none");
            put("MILD", "dep2_mild");
        }});
        config2.setFallMethodField("fallField2");
        config2.setFallMethodOptions(new LinkedHashMap<String, String>() {{
            put("CLINICAL", "fall2_clinical");
            put("MORSE", "fall2_morse");
        }});

        props.getFormOptionConfigs().put("ruyuanhulipinggudan", config2);

        Bedside adl = buildBedside("p1", "param_score_adl", "90（轻度依赖）",
                parseDate("2026-08-01T11:00:00"), new Date(), "id1");
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", Map.of("param_score_adl", adl));

        // formCode=zhuanruhulipinggudan → 硬编码逻辑：轻度依赖 → shzlnl2
        Map<String, Object> result1 = selector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap(),
                "zhuanruhulipinggudan");
        assertEquals(Collections.singletonList("qingduyilai"), result1.get("shzlnl2"));

        // formCode=ruyuanhulipinggudan → 同样硬编码逻辑
        Map<String, Object> result2 = selector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap(),
                "ruyuanhulipinggudan");
        assertEquals(Collections.singletonList("qingduyilai"), result2.get("shzlnl2"));
    }

    // ── 已有值相同但顺序不同，不产生数据库更新 ────────────────────────

    @Test
    void candidate_fallMethod_orderInsensitive() {
        Score score = new Score();
        score.setPid("p1");
        score.setPatientFallDangerFactorV2(new HashMap<String, Object>() {{
            put("age", true);
            put("fallHistory", 15);
        }});
        Map<String, Score> scoreMap = Map.of("p1", score);
        Map<String, Object> result = selector.buildCandidateValues("p1", Collections.emptyMap(), scoreMap, FORM_CODE);
        // 临床判定法 → lcpdf, Morse → mpff（分别写入不同字段）
        assertEquals(Collections.singletonList(FALL_OPT_CLINICAL), result.get(FALL_FIELD_1));
        assertEquals(Collections.singletonList(FALL_OPT_MORSE), result.get(FALL_FIELD_2));
    }

    // ── 未配置真实编码时不写入、不猜测 ──────────────────────────────

    @Test
    void candidate_noConfig_skipsAll() {
        FirstAdmissionAssessmentSyncProperties emptyProps = new FirstAdmissionAssessmentSyncProperties();
        emptyProps.setFormOptionConfigs(new LinkedHashMap<>());
        FirstAssessmentSourceSelector emptySelector = new FirstAssessmentSourceSelector(emptyProps);

        Bedside adl = buildBedside("p1", "param_score_adl", "90（轻度依赖）",
                parseDate("2026-08-01T11:00:00"), new Date(), "id1");
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", Map.of("param_score_adl", adl));

        Score score = new Score();
        score.setPid("p1");
        score.setPatientFallDangerFactorV2(new HashMap<String, Object>() {{
            put("age", true);
            put("fallHistory", 15);
        }});
        Map<String, Score> scoreMap = Map.of("p1", score);

        Map<String, Object> result = emptySelector.buildCandidateValues("p1", bedsideMap, scoreMap, FORM_CODE);
        // 分数仍然写入 shzlnl（SCORE_FIELD_MAPPING 与配置无关）
        assertEquals("90", result.get("shzlnl"));
        // 但选项字段未配置，不写入选项编码
        assertNull(result.get(DEP_FIELD));
        assertNull(result.get(FALL_FIELD_1));
    }

    @Test
    void candidate_morseEmpty_false() {
        Score score = new Score();
        score.setPid("p1");
        Map<String, Object> factor = new HashMap<>();
        factor.put("fallHistory", null);
        factor.put("otherDiagnosis", null);
        factor.put("useWalkTool", null);
        score.setPatientFallDangerFactorV2(factor);
        Map<String, Score> scoreMap = Map.of("p1", score);
        Map<String, Object> result = selector.buildCandidateValues("p1", Collections.emptyMap(), scoreMap, FORM_CODE);
        assertNull(result.get(FALL_FIELD_1));
    }

    @Test
    void candidate_factorNull_noException() {
        Score score = new Score();
        score.setPid("p1");
        score.setPatientFallDangerFactorV2(null);
        Map<String, Score> scoreMap = Map.of("p1", score);
        assertDoesNotThrow(() ->
                selector.buildCandidateValues("p1", Collections.emptyMap(), scoreMap, FORM_CODE));
    }

    @Test
    void candidate_dependencyNoBracket_noOptionField() {
        Bedside adl = buildBedside("p1", "param_score_adl", "90",
                parseDate("2026-08-01T11:00:00"), new Date(), "id1");
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", Map.of("param_score_adl", adl));
        Map<String, Object> result = selector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap(), FORM_CODE);
        // 分数写入 shzlnl
        assertEquals("90", result.get("shzlnl"));
        // 无括号结论 → 不写入选项字段
        assertNull(result.get(DEP_FIELD));
    }

    @Test
    void candidate_fallMethod_returnsList() {
        Score score = new Score();
        score.setPid("p1");
        score.setPatientFallDangerFactorV2(new HashMap<String, Object>() {{
            put("fallHistory", 15);
        }});
        Map<String, Score> scoreMap = Map.of("p1", score);
        Map<String, Object> result = selector.buildCandidateValues("p1", Collections.emptyMap(), scoreMap, FORM_CODE);
        // Morse → 第二个字段 (mpff)
        assertTrue(result.get(FALL_FIELD_2) instanceof List);
    }

    // ══════════════════════════════════════════════════════════════════
    // 生命体征：xy / tw / mb / hx
    // ══════════════════════════════════════════════════════════════════

    @Test
    void vitalSigns_xy() {
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", new LinkedHashMap<>(Map.of(
                "param_nibp_s", buildBedside("p1", "param_nibp_s", "120", parseDate("2026-08-01T11:00:00"), new Date(), "id1"),
                "param_nibp_d", buildBedside("p1", "param_nibp_d", "80", parseDate("2026-08-01T11:00:00"), new Date(), "id2")
        )));
        Map<String, Object> result = selector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap(), FORM_CODE);
        assertEquals("120/80", result.get("xy"));
        assertNull(result.get("nibp_s"));
        assertNull(result.get("nibp_d"));
    }

    @Test
    void vitalSigns_tw() {
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", Map.of(
                "param_T", buildBedside("p1", "param_T", "36.5", parseDate("2026-08-01T11:00:00"), new Date(), "id1")
        ));
        Map<String, Object> result = selector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap(), FORM_CODE);
        assertEquals("36.5", result.get("tw"));
    }

    @Test
    void vitalSigns_mb_pulseFirst() {
        // 有脉搏时优先脉搏
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", new LinkedHashMap<>(Map.of(
                "param_脉搏", buildBedside("p1", "param_脉搏", "72", parseDate("2026-08-01T11:00:00"), new Date(), "id1"),
                "param_HR", buildBedside("p1", "param_HR", "75", parseDate("2026-08-01T11:00:00"), new Date(), "id2")
        )));
        Map<String, Object> result = selector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap(), FORM_CODE);
        assertEquals("72", result.get("mb"));
    }

    @Test
    void vitalSigns_mb_fallbackToHr() {
        // 无脉搏时兜底心率
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", Map.of(
                "param_HR", buildBedside("p1", "param_HR", "75", parseDate("2026-08-01T11:00:00"), new Date(), "id1")
        ));
        Map<String, Object> result = selector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap(), FORM_CODE);
        assertEquals("75", result.get("mb"));
    }

    @Test
    void vitalSigns_mb_none() {
        // 两者都没有时不写入
        Map<String, Object> result = selector.buildCandidateValues("p1", Collections.emptyMap(), Collections.emptyMap(), FORM_CODE);
        assertNull(result.get("mb"));
    }

    @Test
    void vitalSigns_hx() {
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", Map.of(
                "param_resp", buildBedside("p1", "param_resp", "18", parseDate("2026-08-01T11:00:00"), new Date(), "id1")
        ));
        Map<String, Object> result = selector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap(), FORM_CODE);
        assertEquals("18", result.get("hx"));
    }

    // ══════════════════════════════════════════════════════════════════
    // 意识状态：yszt1 + yszt8
    // ══════════════════════════════════════════════════════════════════

    @Test
    void consciousness_qingchu() {
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", Map.of(
                "param_Yishi", buildBedside("p1", "param_Yishi", "清楚", parseDate("2026-08-01T11:00:00"), new Date(), "id1")
        ));
        Map<String, Object> result = selector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap(), FORM_CODE);
        assertEquals("qingchu", result.get("yszt1"));
        assertNull(result.get("yszt8"));
    }

    @Test
    void consciousness_hunshui() {
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", Map.of(
                "param_Yishi", buildBedside("p1", "param_Yishi", "昏睡", parseDate("2026-08-01T11:00:00"), new Date(), "id1")
        ));
        Map<String, Object> result = selector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap(), FORM_CODE);
        assertEquals("hunshui", result.get("yszt1"));
        assertNull(result.get("yszt8"));
    }

    @Test
    void consciousness_shishui() {
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", Map.of(
                "param_Yishi", buildBedside("p1", "param_Yishi", "嗜睡", parseDate("2026-08-01T11:00:00"), new Date(), "id1")
        ));
        Map<String, Object> result = selector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap(), FORM_CODE);
        assertEquals("shishui", result.get("yszt1"));
    }

    @Test
    void consciousness_qingduhunmi() {
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", Map.of(
                "param_Yishi", buildBedside("p1", "param_Yishi", "轻度昏迷", parseDate("2026-08-01T11:00:00"), new Date(), "id1")
        ));
        Map<String, Object> result = selector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap(), FORM_CODE);
        assertEquals("qingduhunmi", result.get("yszt1"));
    }

    @Test
    void consciousness_zhongduhunmi() {
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", Map.of(
                "param_Yishi", buildBedside("p1", "param_Yishi", "中度昏迷", parseDate("2026-08-01T11:00:00"), new Date(), "id1")
        ));
        Map<String, Object> result = selector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap(), FORM_CODE);
        assertEquals("zhongduhunmi", result.get("yszt1"));
    }

    @Test
    void consciousness_shenduhunmi() {
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", Map.of(
                "param_Yishi", buildBedside("p1", "param_Yishi", "深度昏迷", parseDate("2026-08-01T11:00:00"), new Date(), "id1")
        ));
        Map<String, Object> result = selector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap(), FORM_CODE);
        assertEquals("shenduhunmi", result.get("yszt1"));
    }

    @Test
    void consciousness_qita_withYszt8() {
        // 不匹配的值：yszt1=qita, yszt8=原始中文
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", Map.of(
                "param_Yishi", buildBedside("p1", "param_Yishi", "谵妄", parseDate("2026-08-01T11:00:00"), new Date(), "id1")
        ));
        Map<String, Object> result = selector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap(), FORM_CODE);
        assertEquals("qita", result.get("yszt1"));
        assertEquals("谵妄", result.get("yszt8"));
    }

    @Test
    void consciousness_null_noWrite() {
        // param_Yishi 不存在时不写入
        Map<String, Object> result = selector.buildCandidateValues("p1", Collections.emptyMap(), Collections.emptyMap(), FORM_CODE);
        assertNull(result.get("yszt1"));
        assertNull(result.get("yszt8"));
    }

    // ══════════════════════════════════════════════════════════════════
    // helpers
    // ══════════════════════════════════════════════════════════════════

    private Bedside buildBedside(String pid, String code, String strVal,
                                  Date time, Date editTime, String id) {
        Bedside b = new Bedside();
        b.setPid(pid);
        b.setCode(code);
        b.setStrVal(strVal);
        b.setValid(true);
        b.setTime(time);
        b.setEditTime(editTime);
        b.setId(id);
        return b;
    }

    private Score buildScore(String pid, Date time, String id) {
        Score s = new Score();
        s.setPid(pid);
        s.setScoreType("patientFallDangerLJRMYY");
        s.setValid(true);
        s.setTime(time);
        s.setEditTime(new Date());
        s.setId(id);
        return s;
    }

    private Date parseDate(String dateTime) {
        return Date.from(LocalDateTime.parse(dateTime)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant());
    }
}
