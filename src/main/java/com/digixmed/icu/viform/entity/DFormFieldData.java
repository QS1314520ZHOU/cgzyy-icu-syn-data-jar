package com.digixmed.icu.viform.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * dFormData.fieldDataList 内嵌项 —— 单个表单字段。
 *
 * <p>value 为 Object 类型，可为 String、Number、List&lt;String&gt; 等，
 * 需要根据字段语义做规范化比较。</p>
 */
@Data
@NoArgsConstructor
public class DFormFieldData {

    /** 字段名，如 "ttpg"、"braden"、"lcpdf" */
    private String field;

    /** 字段值（可为 String、Number、List&lt;String&gt; 等） */
    private Object value;

    public DFormFieldData(String field, Object value) {
        this.field = field;
        this.value = value;
    }
}
