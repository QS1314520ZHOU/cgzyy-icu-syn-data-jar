package com.digixmed.icu.viform.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Date;

/**
 * 血糖推送请求 DTO —— 平台方通过 POST /data/bloodSugar 推送的数据体。
 *
 * <p>operationType 兼容 int 与字符串两种传入形式，Jackson 默认会尝试转换。</p>
 */
@Data
public class BloodSugarPushDTO {

    /** 病人ID（必填） */
    @JsonProperty(required = true)
    private String patId;

    /** 病人姓名（必填） */
    @JsonProperty(required = true)
    private String patName;

    /**
     * 病人号（必填）—— 门诊号/住院号，对应 patient.mrn。
     */
    @JsonProperty(required = true)
    private String patNo;

    /** 入院时间（必填），格式 yyyy-MM-dd HH:mm:ss */
    @JsonProperty(required = true)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date hosInDate;

    /** 血糖编号（必填） */
    @JsonProperty(required = true)
    private String bloGluNum;

    /** 血糖值（必填） */
    @JsonProperty(required = true)
    private String bloGluVal;

    /** 采血时间（必填），格式 yyyy-MM-dd HH:mm:ss */
    @JsonProperty(required = true)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date bloGluDateTime;

    /** 时间段（必填），如"早餐前" */
    @JsonProperty(required = true)
    private String timePeriod;

    /** 时间段类型（必填） */
    @JsonProperty(required = true)
    private String timePeriodType;

    /** 操作护士姓名（必填） */
    @JsonProperty(required = true)
    private String operatNurse;

    /** 血糖备注（选填） */
    private String bloGluNote;

    /** 病区编码（必填） */
    @JsonProperty(required = true)
    private String wardCode;

    /** 病区名称（必填） */
    @JsonProperty(required = true)
    private String wardName;

    /** 床号（必填） */
    @JsonProperty(required = true)
    private String patBedNo;

    /**
     * 操作类型（必填）：1=保存 2=修改 3=删除。
     *
     * <p>兼容 int 与字符串（如 "1"），Jackson 默认可转换。</p>
     */
    @JsonProperty(required = true)
    private Integer operationType;

    /** 机构ID（必填） */
    @JsonProperty(required = true)
    private String orgId;
}
