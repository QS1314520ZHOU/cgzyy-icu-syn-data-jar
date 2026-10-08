package com.digixmed.icu.viform.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DescUtils} 结尾标点统一（护理记录描述以「。」结尾）测试。
 */
class DescUtilsTest {

    // ===== withPeriodEnding =====

    @Test
    void withPeriodEnding_replacesTrailingSemicolon() {
        assertEquals("皮肤情况：压红；护理措施：换药。",
                DescUtils.withPeriodEnding("皮肤情况：压红；护理措施：换药；"));
    }

    @Test
    void withPeriodEnding_replacesTrailingHalfWidthSemicolon() {
        assertEquals("胃管：置入长度:55cm;固定情况:妥善固定。",
                DescUtils.withPeriodEnding("胃管：置入长度:55cm;固定情况:妥善固定;"));
    }

    @Test
    void withPeriodEnding_appendsWhenNoPunctuation() {
        assertEquals("GCS评分：E3V5M6分；疼痛评分：NRS-0分。",
                DescUtils.withPeriodEnding("GCS评分：E3V5M6分；疼痛评分：NRS-0分"));
    }

    @Test
    void withPeriodEnding_idempotent() {
        String once = DescUtils.withPeriodEnding("皮肤情况：压红");
        assertEquals(once, DescUtils.withPeriodEnding(once));
    }

    @Test
    void withPeriodEnding_emptyOrNull() {
        assertEquals("", DescUtils.withPeriodEnding(null));
        assertEquals("", DescUtils.withPeriodEnding("  "));
        assertEquals("", DescUtils.withPeriodEnding("；、"));
    }

    // ===== stripEnding =====

    @Test
    void stripEnding_removesTrailingPunctuationOnly() {
        assertEquals("皮肤情况：压红；护理措施：换药",
                DescUtils.stripEnding("皮肤情况：压红；护理措施：换药；"));
        assertEquals("皮肤情况：压红",
                DescUtils.stripEnding("皮肤情况：压红；"));
        assertEquals("皮肤情况：压红",
                DescUtils.stripEnding("皮肤情况：压红；。  "));
        assertEquals("", DescUtils.stripEnding(null));
    }

    // ===== merge =====

    @Test
    void merge_noDoublePeriodBeforeSemicolon() {
        // 已有记录已以「。」结尾，合并后不应出现「。；」
        assertEquals("患者无不适；GCS评分：E3V5M6分。",
                DescUtils.merge("患者无不适。", "GCS评分：E3V5M6分"));
    }

    @Test
    void merge_stripsLegacyTrailingSemicolon() {
        // 历史数据结尾是「；」，合并后不应出现「；；」
        assertEquals("皮肤情况：压红；护理措施：换药。",
                DescUtils.merge("皮肤情况：压红；", "护理措施：换药；"));
    }

    @Test
    void merge_keepsInternalSemicolons() {
        assertEquals("A；B；C。",
                DescUtils.merge("A；B", "C"));
    }

    @Test
    void merge_leftOnly() {
        assertEquals("GCS评分：E3V5M6分。", DescUtils.merge(null, "GCS评分：E3V5M6分"));
        assertEquals("GCS评分：E3V5M6分。", DescUtils.merge("", "GCS评分：E3V5M6分；"));
    }

    @Test
    void merge_rightOnly() {
        assertEquals("GCS评分：E3V5M6分。", DescUtils.merge("GCS评分：E3V5M6分；", null));
        assertEquals("GCS评分：E3V5M6分。", DescUtils.merge("GCS评分：E3V5M6分", ""));
    }

    @Test
    void merge_bothEmpty() {
        assertEquals("", DescUtils.merge(null, null));
        assertEquals("", DescUtils.merge("", "  "));
        assertEquals("", DescUtils.merge("；", null));
    }

    @Test
    void merge_newContentFirst_prependStyle() {
        // 管道同步是把新内容拼在已有记录前面，只保留整段末尾的一个「。」
        assertEquals("胃管：留置天数:3天；患者无不适。",
                DescUtils.merge("胃管：留置天数:3天;", "患者无不适。"));
    }
}
