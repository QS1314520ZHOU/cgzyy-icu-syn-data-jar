package com.digixmed.icu.viform.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

/**
 * SmartCare 库 bloodSugar 集合实体 —— 血糖检测记录。
 *
 * <p>示例文档结构：</p>
 * <pre>
 * {
 *   _id, pid, time(ISODate,UTC), specimenSource, result,
 *   examiner, examinerId, remarks, valid(Boolean),
 *   detectionProject, deviceCode, _class
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

    /** 标本来源，如 "动脉血" / "毛细血管" */
    private String specimenSource;

    /** 检测结果值（血糖值） */
    private String result;

    /** 检测人姓名 */
    private String examiner;

    /** 检测人 ID */
    private String examinerId;

    /** 备注 */
    private String remarks;

    /** 是否有效 */
    private Boolean valid;

    /** 检测项目，如 "血糖" */
    private String detectionProject;

    /** 设备编码 */
    private String deviceCode;
}
