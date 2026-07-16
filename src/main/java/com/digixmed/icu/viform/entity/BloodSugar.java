package com.digixmed.icu.viform.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

/**
 * SmartCare 库 bloodSugar 集合实体 —— 血糖检测记录。
 *
 * <p>主键为 MongoDB 原生 ObjectId（String），由数据库自动生成。</p>
 *
 * <p>存量文档 _class：
 * {@code com.digixmed.icu.smartcare.database.entitys.bloodSugar.BloodSugar}</p>
 *
 * <p>示例文档结构：</p>
 * <pre>
 * {
 *   _id: ObjectId, pid, time(ISODate,UTC), specimenSource, result,
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

    /** MongoDB 主键 (_id)，ObjectId 字符串 */
    @Id
    private String id;

    /** 患者 ID，对应 patient._id */
    private String pid;

    /** 检测时间（UTC 存储） */
    private Date time;

    /** 标本来源，如 "动脉血" / "毛细血管"（TODO：确认业务默认值） */
    private String specimenSource;

    /** 检测结果值（血糖值） */
    private String result;

    /** 检测人姓名 */
    private String examiner;

    /** 检测人 ID（从 account 集合按 trueName 匹配） */
    private String examinerId;

    /** 备注（存量字段，推送可不写） */
    private String remarks;

    /** 是否有效 */
    private Boolean valid;

    /** 检测项目，如 "早餐前"（映射自 timePeriod） */
    private String detectionProject;

    /** 设备编码（TODO：确认业务默认值） */
    private String deviceCode;
}
