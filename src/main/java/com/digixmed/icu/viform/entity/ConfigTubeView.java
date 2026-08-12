package com.digixmed.icu.viform.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

/**
 * 管道配置视图实体。
 * 对应 SmartCare 库的 configTubeView 集合。
 */
@Data
@Document("configTubeView")
public class ConfigTubeView {

    /** SmartCare 原始类名 */
    public static final String CONFIG_TUBE_VIEW_CLASS = "com.digixmed.icu.smartcare.database.entitys.tubeConfig.ConfigTubeView";

    @Id
    private String id;

    /** 管道类型（如 "尿管"、"中长导管"） */
    private String tubeType;

    /** 是否有效 */
    private Boolean valid;

    /** 是否可编辑 */
    private Boolean canEdit;

    /** 是否显示时间段 */
    private Boolean showTimeFrame;

    /** 管道字段配置列表 */
    private List<TubeFieldConfig> tubeFieldConfigList;

    /** 管道记录字段配置列表 */
    private List<TubeFieldConfig> tubeRecordFieldConfigList;

    /** 是否可复制 */
    private Boolean copy;

    /** 管道维护标记 */
    private Boolean tubeMaintain;

    /** 一键拔管标记 */
    private Boolean aKeyCancelTube;
}
