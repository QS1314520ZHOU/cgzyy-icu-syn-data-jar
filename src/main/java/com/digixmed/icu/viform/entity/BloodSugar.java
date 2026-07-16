package com.digixmed.icu.viform.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

/**
 * SmartCare 库 bloodSugar 集合实体 —— 血糖检测记录。
 *
 * <p>主键为 Long 自增（通过 SequenceGeneratorService 生成），非 ObjectId。</p>
 *
 * <p>示例文档结构：</p>
 * <pre>
 * {
 *   _id: 1(Long), pid, time(ISODate,UTC), specimenSource, result,
 *   examiner, examinerId, remarks, valid(Boolean),
 *   detectionProject, deviceCode,
 *   bloGluNum, timePeriod, timePeriodType, patName, patNo,
 *   hosInDate, wardCode, wardName, patBedNo, orgId, _class
 * }
 * </pre>
 */
@Data
@Document("bloodSugar")
public class BloodSugar {

    /** 自增主键 (_id) */
    @Id
    private Long id;

    /** 患者 ID，对应 patient._id */
    private String pid;

    /** 检测时间（UTC 存储） */
    private Date time;

    /** 标本来源，如 "动脉血" / "毛细血管" */
    private String specimenSource;

    /** 检测结果值（血糖值） */
    private String result;

    /** 检测人姓名 */
    private String examiner;

    /** 检测人 ID（从 account 集合按姓名匹配） */
    private String examinerId;

    /** 备注 */
    private String remarks;

    /** 是否有效 */
    private Boolean valid;

    /** 检测项目，如 "早餐前" */
    private String detectionProject;

    /** 设备编码 */
    private String deviceCode;

    // ==================== 平台推送扩展字段 ====================

    /** 血糖编号（平台推送） */
    private String bloGluNum;

    /** 时间段，如"早餐前"（平台推送） */
    private String timePeriod;

    /** 时间段类型（平台推送） */
    private String timePeriodType;

    /** 病人姓名（平台推送） */
    private String patName;

    /** 病人号（平台推送，门诊号/住院号） */
    private String patNo;

    /** 入院时间（平台推送） */
    private Date hosInDate;

    /** 病区编码（平台推送） */
    private String wardCode;

    /** 病区名称（平台推送） */
    private String wardName;

    /** 床号（平台推送） */
    private String patBedNo;

    /** 机构ID（平台推送） */
    private String orgId;
}
