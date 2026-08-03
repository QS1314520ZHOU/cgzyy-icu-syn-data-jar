package com.digixmed.icu.viform.service;

import com.digixmed.icu.viform.config.FirstAdmissionAssessmentSyncProperties;
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

    @BeforeEach
    void setUp() {
        FirstAdmissionAssessmentSyncProperties props = new FirstAdmissionAssessmentSyncProperties();
        props.setClinicalMethodValue("testClinicalCode");
        selector = new FirstAssessmentSourceSelector(props);
    }

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

    @Test
    void candidate_ttpgMapping() {
        Bedside teng = buildBedside("p1", "param_tengTong_score", "3",
                parseDate("2026-08-01T11:00:00"), new Date(), "id1");
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", Map.of("param_tengTong_score", teng));
        Map<String, Object> result = selector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap());
        assertEquals("3", result.get("ttpg"));
    }

    @Test
    void candidate_bradenWithConclusion() {
        Bedside braden = buildBedside("p1", "param_yaChuang_score", "12(高度危险)",
                parseDate("2026-08-01T11:00:00"), new Date(), "id1");
        Map<String, Map<String, Bedside>> bedsideMap = Map.of("p1", Map.of("param_yaChuang_score", braden));
        Map<String, Object> result = selector.buildCandidateValues("p1", bedsideMap, Collections.emptyMap());
        assertEquals("12(高度危险)", result.get("braden"));
        assertEquals("高度危险", result.get("branden2"));
    }

    @Test
    void candidate_scoreMorseTotal() {
        Score score = new Score();
        score.setPid("p1");
        score.setTotal(35);
        score.setConclusion("低风险");
        Map<String, Score> scoreMap = Map.of("p1", score);
        Map<String, Object> result = selector.buildCandidateValues("p1", Collections.emptyMap(), scoreMap);
        assertEquals("35", result.get("morde"));
        assertEquals("低风险", result.get("morde2"));
    }

    @Test
    void candidate_clinicalJudgmentTrue() {
        Score score = new Score();
        score.setPid("p1");
        score.setPatientFallDangerFactorV2(Map.of("age", true));
        Map<String, Score> scoreMap = Map.of("p1", score);
        Map<String, Object> result = selector.buildCandidateValues("p1", Collections.emptyMap(), scoreMap);
        assertEquals(Collections.singletonList("testClinicalCode"), result.get("lcpdf"));
    }

    @Test
    void candidate_clinicalJudgmentFalse() {
        Score score = new Score();
        score.setPid("p1");
        score.setPatientFallDangerFactorV2(Map.of("age", "false"));
        Map<String, Score> scoreMap = Map.of("p1", score);
        Map<String, Object> result = selector.buildCandidateValues("p1", Collections.emptyMap(), scoreMap);
        assertNull(result.get("lcpdf"));
    }

    @Test
    void candidate_clinicalJudgmentStringTrue() {
        Score score = new Score();
        score.setPid("p1");
        score.setPatientFallDangerFactorV2(Map.of("age", "true"));
        Map<String, Score> scoreMap = Map.of("p1", score);
        Map<String, Object> result = selector.buildCandidateValues("p1", Collections.emptyMap(), scoreMap);
        assertEquals(Collections.singletonList("testClinicalCode"), result.get("lcpdf"));
    }

    @Test
    void candidate_clinicalJudgmentString1NotTrue() {
        Score score = new Score();
        score.setPid("p1");
        score.setPatientFallDangerFactorV2(Map.of("age", "1"));
        Map<String, Score> scoreMap = Map.of("p1", score);
        Map<String, Object> result = selector.buildCandidateValues("p1", Collections.emptyMap(), scoreMap);
        assertNull(result.get("lcpdf"));
    }

    @Test
    void candidate_morseHasNumber_true() {
        Score score = new Score();
        score.setPid("p1");
        score.setPatientFallDangerFactorV2(Map.of("fallHistory", 15));
        Map<String, Score> scoreMap = Map.of("p1", score);
        Map<String, Object> result = selector.buildCandidateValues("p1", Collections.emptyMap(), scoreMap);
        assertEquals(Collections.singletonList("Mordepingfenfa"), result.get("mpff"));
    }

    @Test
    void candidate_morseHasNumberString_true() {
        Score score = new Score();
        score.setPid("p1");
        score.setPatientFallDangerFactorV2(Map.of("fallHistory", "0"));
        Map<String, Score> scoreMap = Map.of("p1", score);
        Map<String, Object> result = selector.buildCandidateValues("p1", Collections.emptyMap(), scoreMap);
        assertEquals(Collections.singletonList("Mordepingfenfa"), result.get("mpff"));
    }

    @Test
    void candidate_morseNonNumeric_false() {
        Score score = new Score();
        score.setPid("p1");
        score.setPatientFallDangerFactorV2(Map.of("fallHistory", "abc"));
        Map<String, Score> scoreMap = Map.of("p1", score);
        Map<String, Object> result = selector.buildCandidateValues("p1", Collections.emptyMap(), scoreMap);
        assertNull(result.get("mpff"));
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
        Map<String, Object> result = selector.buildCandidateValues("p1", Collections.emptyMap(), scoreMap);
        assertNull(result.get("mpff"));
    }

    @Test
    void candidate_factorNull_noException() {
        Score score = new Score();
        score.setPid("p1");
        score.setPatientFallDangerFactorV2(null);
        Map<String, Score> scoreMap = Map.of("p1", score);
        assertDoesNotThrow(() ->
                selector.buildCandidateValues("p1", Collections.emptyMap(), scoreMap));
    }

    @Test
    void candidate_clinicalNotConfigured_skipped() {
        FirstAdmissionAssessmentSyncProperties props = new FirstAdmissionAssessmentSyncProperties();
        props.setClinicalMethodValue(null);
        FirstAssessmentSourceSelector noConfigSelector = new FirstAssessmentSourceSelector(props);

        Score score = new Score();
        score.setPid("p1");
        score.setPatientFallDangerFactorV2(Map.of("age", true));
        Map<String, Score> scoreMap = Map.of("p1", score);
        Map<String, Object> result = noConfigSelector.buildCandidateValues("p1", Collections.emptyMap(), scoreMap);
        assertNull(result.get("lcpdf"));
    }

    @Test
    void candidate_mpff_returnsList() {
        Score score = new Score();
        score.setPid("p1");
        score.setPatientFallDangerFactorV2(Map.of("fallHistory", 15));
        Map<String, Score> scoreMap = Map.of("p1", score);
        Map<String, Object> result = selector.buildCandidateValues("p1", Collections.emptyMap(), scoreMap);
        assertTrue(result.get("mpff") instanceof List);
    }

    // ==================== helpers ====================

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
