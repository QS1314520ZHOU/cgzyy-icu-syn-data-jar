package com.digixmed.icu.viform.entity;

import lombok.Data;

import java.util.List;

/**
 * 管道字段配置内嵌项。
 * 对应 configTubeView.tubeRecordFieldConfigList 中的元素。
 */
@Data
public class TubeFieldConfig {

    /** 显示名称（如 "管道状态"、"引流液颜色"） */
    private String name;

    /** 字段名（如 "tubeStatus"、"color"） */
    private String field;

    /** 可选值列表 */
    private List<String> valueList;

    /** 组件类型（如下拉框、输入框等） */
    private String componentType;

    /** 是否多选 */
    private Boolean isMultipleChoice;
}
