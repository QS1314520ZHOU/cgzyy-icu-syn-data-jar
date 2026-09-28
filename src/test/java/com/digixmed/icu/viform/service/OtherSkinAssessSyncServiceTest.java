package com.digixmed.icu.viform.service;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link OtherSkinAssessSyncService#cleanPunctuation(String)} 标点清理、
 * {@link OtherSkinAssessSyncService#isInvalidStatus(Document)} 无效数据过滤测试。
 */
class OtherSkinAssessSyncServiceTest {

    // ===== 需求示例：无红肿、；护理措施 → 无红肿；护理措施 =====

    @Test
    void cleanPunctuation_exampleFromRequirement() {
        assertEquals("无红肿；护理措施",
                OtherSkinAssessSyncService.cleanPunctuation("无红肿、；护理措施"));
    }

    @Test
    void cleanPunctuation_fullSampleSkinMessage() {
        String raw = "鼻梁；长：1cm，宽：2cm，深：3c，分期：引流管；皮肤情况：压红；"
                + "创面情况：渗血渗液(水泡)、；渗液量：中量渗液(5-10ml)；基底颜色：黄；气味：异味；"
                + "分泌物性状：清澈、；周围皮肤：无红肿、；护理措施：腔隙或窦道无菌空针冲洗、；"
                + "敷料应用：透明贴、美皮康银离子；转归情况：未愈合；";
        String expected = "鼻梁；长：1cm，宽：2cm，深：3c，分期：引流管；皮肤情况：压红；"
                + "创面情况：渗血渗液(水泡)；渗液量：中量渗液(5-10ml)；基底颜色：黄；气味：异味；"
                + "分泌物性状：清澈；周围皮肤：无红肿；护理措施：腔隙或窦道无菌空针冲洗；"
                + "敷料应用：透明贴、美皮康银离子；转归情况：未愈合";
        assertEquals(expected, OtherSkinAssessSyncService.cleanPunctuation(raw));
    }

    // ===== 列表项之间的顿号保留 =====

    @Test
    void cleanPunctuation_keepsInternalListSeparator() {
        assertEquals("敷料应用：透明贴、美皮康银离子；转归情况：未愈合",
                OtherSkinAssessSyncService.cleanPunctuation("敷料应用：透明贴、美皮康银离子；转归情况：未愈合；"));
    }

    @Test
    void cleanPunctuation_keepsMultiValueList() {
        assertEquals("后枕部；皮肤情况：完整、压红",
                OtherSkinAssessSyncService.cleanPunctuation("后枕部；皮肤情况：完整、压红"));
    }

    // ===== 其他多余标点 =====

    @Test
    void cleanPunctuation_commaBeforeSemicolon() {
        assertEquals("皮肤情况：压红；护理措施：换药",
                OtherSkinAssessSyncService.cleanPunctuation("皮肤情况：压红，；护理措施：换药"));
    }

    @Test
    void cleanPunctuation_collapsesRepeatedSemicolons() {
        assertEquals("皮肤情况：压红；护理措施：换药",
                OtherSkinAssessSyncService.cleanPunctuation("皮肤情况：压红；；护理措施：换药"));
    }

    @Test
    void cleanPunctuation_stripsLeadingAndTrailingSeparators() {
        assertEquals("皮肤情况：压红",
                OtherSkinAssessSyncService.cleanPunctuation("；、皮肤情况：压红、；"));
    }

    @Test
    void cleanPunctuation_emptyOrNull() {
        assertEquals("", OtherSkinAssessSyncService.cleanPunctuation(null));
        assertEquals("", OtherSkinAssessSyncService.cleanPunctuation("  "));
        assertEquals("", OtherSkinAssessSyncService.cleanPunctuation("；、"));
    }

    @Test
    void cleanPunctuation_noChangeForCleanText() {
        assertEquals("后枕部；皮肤情况：压红",
                OtherSkinAssessSyncService.cleanPunctuation("后枕部；皮肤情况：压红"));
    }

    // ===== status=invalid 无效数据过滤 =====

    @Test
    void isInvalidStatus_invalidFiltered() {
        assertTrue(OtherSkinAssessSyncService.isInvalidStatus(new Document("status", "invalid")));
        assertTrue(OtherSkinAssessSyncService.isInvalidStatus(new Document("status", "Invalid")));
    }

    @Test
    void isInvalidStatus_validKept() {
        assertFalse(OtherSkinAssessSyncService.isInvalidStatus(new Document("status", "valid")));
        assertFalse(OtherSkinAssessSyncService.isInvalidStatus(new Document("status", "INVALID2")));
    }

    @Test
    void isInvalidStatus_missingStatusKept() {
        assertFalse(OtherSkinAssessSyncService.isInvalidStatus(new Document()));
        assertFalse(OtherSkinAssessSyncService.isInvalidStatus(new Document("status", "")));
        assertFalse(OtherSkinAssessSyncService.isInvalidStatus(null));
    }
}
