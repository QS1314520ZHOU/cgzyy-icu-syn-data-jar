package com.digixmed.icu.viform.entity;

import lombok.Data;

import java.util.Date;

/**
 * 管道护理记录内嵌项。
 * 对应 tubeExe.tubeRecordList 中的元素。
 */
@Data
public class TubeRecord {

    /** 记录 ID */
    private Long id;

    /** 护理时间 */
    private Date time;

    /** 操作人 ID */
    private String recordUser;

    /** 操作人姓名 */
    private String recordUserName;

    /** 引流液颜色 */
    private String color;

    /** 管道状态 */
    private String tubeStatus;

    /** 引流方式 */
    private String drainageWay;

    /** 性状 */
    private String character;

    /** 导管护理 */
    private String catheterNurse;

    /** 置管血管（中长导管等使用） */
    private String catheterVessel;

    /** 置管长度 */
    private String catheterLength;

    /** 外露长度 */
    private String exposedLength;

    /** 固定方式 */
    private String fixationMethod;

    /** 是否有效 */
    private Boolean valid;
}
