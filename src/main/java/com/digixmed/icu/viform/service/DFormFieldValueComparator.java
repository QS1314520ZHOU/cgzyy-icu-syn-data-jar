package com.digixmed.icu.viform.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表单字段值规范化比较器。
 *
 * <p>支持 String、Number、List&lt;String&gt; 的规范化比较，
 * 避免因类型/空格/顺序差异导致无意义更新。</p>
 */
@Slf4j
@Component
public class DFormFieldValueComparator {

    /**
     * 提取括号内结论内容（支持全角/半角括号）。
     * <p>示例：3(高度危险) → "高度危险"，12（中度危险）→ "中度危险"</p>
     */
    private static final Pattern PAREN_PATTERN =
            Pattern.compile("[（(]\\s*(.+?)\\s*[）)]");

    /**
     * 值规范化比较：根据字段类型执行规范化比较。
     *
     * @param field    字段名
     * @param oldValue 目标当前值
     * @param newValue 源值
     * @return 是否相同
     */
    public boolean valuesEqual(String field, Object oldValue, Object newValue) {
        // 空值处理：两个都空视为相同
        if (isEmptyValue(oldValue) && isEmptyValue(newValue)) {
            return true;
        }
        // 一个空一个非空：不同
        if (isEmptyValue(oldValue) || isEmptyValue(newValue)) {
            return false;
        }
        // 数值字段使用数值比较
        if (isNumericField(field)) {
            return numericValuesEqual(oldValue, newValue);
        }
        // List 字段使用列表比较
        if (oldValue instanceof List || newValue instanceof List) {
            return listValuesEqual(oldValue, newValue);
        }
        // 默认使用字符串比较
        return stringValuesEqual(oldValue, newValue);
    }

    /**
     * 为写入做值规范化：尽量保持目标字段原有类型。
     */
    public Object normalizeForWrite(String field, Object oldValue, Object sourceValue) {
        if (sourceValue == null) return null;
        // List<String> 字段直接返回（已在 buildCandidateValues 中构造好）
        if (sourceValue instanceof List) {
            return sourceValue;
        }
        if (isNumericField(field)) {
            // 尽量保持目标原有类型
            if (oldValue instanceof Number) {
                BigDecimal bd = toBigDecimal(sourceValue);
                return bd != null ? bd.doubleValue() : sourceValue;
            }
            // 目标不存在时，返回规范化的字符串
            String s = normalizeString(sourceValue);
            return s != null ? s : sourceValue;
        }
        if (sourceValue instanceof String) {
            String s = normalizeString(sourceValue);
            return s != null ? s : sourceValue;
        }
        return sourceValue;
    }

    /**
     * 提取括号中的结论（支持全角半角）。
     *
     * @return 括号内的内容（已 trim），如果没有括号或内容为空则返回 Optional.empty()
     */
    public Optional<String> extractParenthesizedConclusion(String value) {
        if (!StringUtils.hasText(value)) return Optional.empty();
        Matcher m = PAREN_PATTERN.matcher(value.trim());
        if (m.find()) {
            String inner = m.group(1).trim();
            if (StringUtils.hasText(inner)) {
                return Optional.of(inner);
            }
        }
        return Optional.empty();
    }

    // ==================== 内部比较方法 ====================

    private boolean stringValuesEqual(Object oldValue, Object newValue) {
        String oldStr = normalizeString(oldValue);
        String newStr = normalizeString(newValue);
        if (oldStr == null && newStr == null) return true;
        if (oldStr == null || newStr == null) return false;
        return oldStr.equals(newStr);
    }

    private boolean numericValuesEqual(Object oldValue, Object newValue) {
        BigDecimal oldBd = toBigDecimal(oldValue);
        BigDecimal newBd = toBigDecimal(newValue);
        if (oldBd == null && newBd == null) return true;
        if (oldBd == null || newBd == null) return false;
        return oldBd.compareTo(newBd) == 0;
    }

    private boolean listValuesEqual(Object oldValue, Object newValue) {
        List<String> oldList = toStringList(oldValue);
        List<String> newList = toStringList(newValue);
        if (oldList == null && newList == null) return true;
        if (oldList == null || newList == null) return false;
        if (oldList.isEmpty() && newList.isEmpty()) return true;
        // 排序后比较（多选字段顺序无业务意义）
        List<String> oldSorted = new ArrayList<>(oldList);
        List<String> newSorted = new ArrayList<>(newList);
        Collections.sort(oldSorted);
        Collections.sort(newSorted);
        return oldSorted.equals(newSorted);
    }

    private List<String> toStringList(Object value) {
        if (value == null) return null;
        if (value instanceof List) {
            List<?> raw = (List<?>) value;
            List<String> result = new ArrayList<>();
            for (Object item : raw) {
                if (item != null) {
                    String s = normalizeString(item.toString());
                    if (s != null) result.add(s);
                }
            }
            return result;
        }
        String s = normalizeString(value);
        return s != null ? Collections.singletonList(s) : null;
    }

    private boolean isEmptyValue(Object value) {
        if (value == null) return true;
        if (value instanceof String) return !StringUtils.hasText((String) value);
        if (value instanceof List) return ((List<?>) value).isEmpty();
        return false;
    }

    private String normalizeString(Object value) {
        if (value == null) return null;
        String s = value instanceof String ? (String) value : value.toString();
        // trim + 统一全角/半角空格
        s = s.replace('　', ' '); // 全角空格→半角
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        String s = normalizeString(value);
        if (s == null) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isNumericField(String field) {
        return "morde".equals(field);
    }
}
