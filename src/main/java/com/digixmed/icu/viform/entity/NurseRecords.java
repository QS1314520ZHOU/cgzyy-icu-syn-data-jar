package com.digixmed.icu.viform.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

/**
 * 护理记录实体。
 * 对应 SmartCare 库的 nurseRecords 集合。
 */
@Data
@Document("nurseRecords")
public class NurseRecords {

    /** SmartCare 原始类名 */
    public static final String NURSE_RECORDS_CLASS = "com.digixmed.icu.smartcare.database.entitys.nursingRecords.NurseRecords";

    @Id
    private String id;

    /** 操作人姓名 */
    private String username;

    /** 操作人真实姓名 */
    private String trueName;

    /** 操作人 ID */
    private String userId;

    /** 患者 ID */
    private String pid;

    /** 患者姓名 */
    private String name;

    /** 护理记录描述 */
    private String desc;

    /** 记录时间 */
    private Date time;

    /** 创建时间 */
    private Date createTime;

    /** 职业 */
    private String professions;

    /** 是否有效 */
    private Boolean valid;

    /** 药物执行手动标记 */
    private Boolean drugExeManualFlag;

    /** 自动同步标记 */
    private Boolean autoSyn;

    /** 使用次数 */
    private Integer useTimes;
}
