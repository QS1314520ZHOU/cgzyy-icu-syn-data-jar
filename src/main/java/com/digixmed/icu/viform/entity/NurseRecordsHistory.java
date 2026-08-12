package com.digixmed.icu.viform.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

/**
 * 护理记录同步历史实体。
 * 用于记录管道护理同步到护理记录的历史，支持去重和覆盖更新。
 */
@Data
@Document("nurseRecordsHistory")
public class NurseRecordsHistory {

    @Id
    private String id;

    /** 患者 ID */
    private String pid;

    /** 管道执行记录 ID (tubeExe._id) */
    private String tubeExeId;

    /** 管道类型 */
    private String tubeType;

    /** 班次类型 (MORNING/AFTERNOON/NIGHT) */
    private String shiftType;

    /** 护理记录时间 */
    private Date tubeRecordTime;

    /** 对应的护理记录 ID (nurseRecords._id) */
    private String nurseRecordId;

    /** 同步时间 */
    private Date syncTime;

    /** 同步的描述内容 */
    private String syncContent;
}
