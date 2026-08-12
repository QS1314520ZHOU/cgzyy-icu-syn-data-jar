package com.digixmed.icu.viform.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.List;

/**
 * 管道护理执行记录实体。
 * 对应 SmartCare 库的 tubeExe 集合。
 */
@Data
@Document("tubeExe")
public class TubeExe {

    /** SmartCare 原始类名 */
    public static final String TUBE_EXE_CLASS = "com.digixmed.icu.smartcare.database.entitys.tubeExe.TubeExe";

    @Id
    private String id;

    /** 患者 ID */
    private String pid;

    /** 管道类型（如 "尿管"、"中长导管"） */
    private String name;

    /** 管道类型（同 name） */
    private String type;

    /** 置管时间 */
    private Date startTime;

    /** 是否 48 小时内不重复评估 */
    private Boolean noCalAgain48h;

    /** 未知开始时间标记 */
    private Boolean unKnownStartTime;

    /** 护理记录列表 */
    private List<TubeRecord> tubeRecordList;
}
