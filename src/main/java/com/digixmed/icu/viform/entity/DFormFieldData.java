package com.digixmed.icu.viform.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * dFormData.fieldDataList 内嵌项 —— 单个表单字段。
 *
 * <p>value 为 Object 类型，可为 String、Number、Boolean、List&lt;String&gt; 等。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DFormFieldData {

    /** 字段名，如 "ttpg"、"braden"、"lcpdf" */
    private String field;

    /** 字段值（可为 String、Number、Boolean、List&lt;String&gt; 等） */
    private Object value;
}
