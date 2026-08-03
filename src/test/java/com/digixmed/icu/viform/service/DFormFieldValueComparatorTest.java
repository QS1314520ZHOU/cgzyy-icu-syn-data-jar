package com.digixmed.icu.viform.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DFormFieldValueComparatorTest {

    private DFormFieldValueComparator comparator;

    @BeforeEach
    void setUp() {
        comparator = new DFormFieldValueComparator();
    }

    // ===== parenthesis parsing =====

    @Test
    void extractParen_halfAngle() {
        assertEquals("高度危险",
                comparator.extractParenthesizedConclusion("3(高度危险)").orElse(null));
    }

    @Test
    void extractParen_fullAngle() {
        assertEquals("高度危险",
                comparator.extractParenthesizedConclusion("3（高度危险）").orElse(null));
    }

    @Test
    void extractParen_withSpaces() {
        assertEquals("高度危险",
                comparator.extractParenthesizedConclusion("3 ( 高度危险 )").orElse(null));
    }

    @Test
    void extractParen_emptyContent() {
        assertFalse(comparator.extractParenthesizedConclusion("3()").isPresent());
    }

    @Test
    void extractParen_incomplete() {
        assertFalse(comparator.extractParenthesizedConclusion("3(高度危险").isPresent());
    }

    @Test
    void extractParen_noParentheses() {
        assertFalse(comparator.extractParenthesizedConclusion("高度危险").isPresent());
    }

    @Test
    void extractParen_null() {
        assertFalse(comparator.extractParenthesizedConclusion(null).isPresent());
    }

    // ===== string comparison =====

    @Test
    void stringEqual_identical() {
        assertTrue(comparator.valuesEqual("braden", "12(高度危险)", "12(高度危险)"));
    }

    @Test
    void stringEqual_trimmed() {
        assertTrue(comparator.valuesEqual("braden", " 高度危险 ", "高度危险"));
    }

    @Test
    void stringEqual_different() {
        assertFalse(comparator.valuesEqual("braden", "高度危险", "极度危险"));
    }

    @Test
    void stringEqual_bothNull() {
        assertTrue(comparator.valuesEqual("braden", null, null));
    }

    @Test
    void stringEqual_oneNull() {
        assertFalse(comparator.valuesEqual("braden", null, "高度危险"));
    }

    @Test
    void stringEqual_emptyString() {
        assertTrue(comparator.valuesEqual("braden", "", null));
    }

    // ===== numeric comparison =====

    @Test
    void numericEqual_intVsString() {
        assertTrue(comparator.valuesEqual("morde", 35, "35"));
    }

    @Test
    void numericEqual_doubleVsString() {
        assertTrue(comparator.valuesEqual("morde", 35.0, "35"));
    }

    @Test
    void numericEqual_differentValues() {
        assertFalse(comparator.valuesEqual("morde", 35, "30"));
    }

    @Test
    void numericEqual_bothNull() {
        assertTrue(comparator.valuesEqual("morde", null, null));
    }

    @Test
    void numericEqual_oneNull() {
        assertFalse(comparator.valuesEqual("morde", null, 35));
    }

    // ===== list comparison =====

    @Test
    void listEqual_sameContent() {
        assertTrue(comparator.valuesEqual("lcpdf",
                Arrays.asList("lcpdf"), Arrays.asList("lcpdf")));
    }

    @Test
    void listEqual_trimmed() {
        assertTrue(comparator.valuesEqual("lcpdf",
                Arrays.asList(" lcpdf "), Arrays.asList("lcpdf")));
    }

    @Test
    void listEqual_differentOrder() {
        assertTrue(comparator.valuesEqual("mpft",
                Arrays.asList("a", "b"), Arrays.asList("b", "a")));
    }

    @Test
    void listEqual_differentContent() {
        assertFalse(comparator.valuesEqual("mpft",
                Arrays.asList("a", "b"), Arrays.asList("a", "c")));
    }

    // ===== normalizeForWrite =====

    @Test
    void normalize_stringField() {
        assertEquals("12(高度危险)",
                comparator.normalizeForWrite("braden", null, " 12(高度危险) "));
    }

    @Test
    void normalize_numericField_fromString() {
        assertEquals("35",
                comparator.normalizeForWrite("morde", null, "35"));
    }

    @Test
    void normalize_numericField_fromNumber() {
        assertEquals(35.0,
                comparator.normalizeForWrite("morde", 30, 35));
    }

    @Test
    void normalize_emptySource() {
        assertNull(comparator.normalizeForWrite("braden", null, null));
    }

    // ===== List<String> comparison =====

    @Test
    void listEqual_identical() {
        assertTrue(comparator.valuesEqual("mpff",
                Arrays.asList("Mordepingfenfa"), Arrays.asList("Mordepingfenfa")));
    }

    @Test
    void listEqual_stringTrimmed() {
        assertTrue(comparator.valuesEqual("mpff",
                Arrays.asList(" Mordepingfenfa "), Arrays.asList("Mordepingfenfa")));
    }

    @Test
    void listEqual_different() {
        assertFalse(comparator.valuesEqual("mpff",
                Arrays.asList("Mordepingfenfa"), Arrays.asList("OtherMethod")));
    }

    @Test
    void listEqual_emptyVsEmpty() {
        assertTrue(comparator.valuesEqual("lcpdf",
                Arrays.asList(), Arrays.asList()));
    }

    @Test
    void listEqual_oneEmptyOneNotEmpty() {
        assertFalse(comparator.valuesEqual("mpff",
                Arrays.asList(), Arrays.asList("Mordepingfenfa")));
    }

    @Test
    void normalize_listField() {
        Object result = comparator.normalizeForWrite("mpff", null,
                Arrays.asList("Mordepingfenfa"));
        assertTrue(result instanceof List);
        assertEquals(Arrays.asList("Mordepingfenfa"), result);
    }
}
