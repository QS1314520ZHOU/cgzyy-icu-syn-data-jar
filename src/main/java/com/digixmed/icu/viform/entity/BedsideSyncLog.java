package com.digixmed.icu.viform.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

/**
 * bedside 同步日志实体（SmartCare 库 bedside_sync_log 集合）。
 *
 * <p>记录 param 定时同步和 order 医嘱同步的每次操作，
 * 同时承担幂等去重职责（sourceKey / pid+code+targetTimePoint 唯一约束）。</p>
 */
@Data
@Document("bedside_sync_log")
@CompoundIndexes({
        /* param 同步幂等：同一患者+code+时间点只同步一次 */
        @CompoundIndex(name = "idx_param_dedup", def = "{'pid': 1, 'code': 1, 'targetTimePoint': 1}"),
        /* order 同步幂等：同一 sourceKey 只同步一次 */
        @CompoundIndex(name = "idx_source_key", def = "{'sourceKey': 1}", unique = true, sparse = true)
})
public class BedsideSyncLog {

    /** MongoDB 主键 (_id) */
    @Id
    private String id;

    /** 同步类型：PARAM定时 / ORDER医嘱 */
    private String syncType;

    /** bedside.code */
    private String code;

    /** 患者 ID (patient._id) */
    private String pid;

    /** 患者 MRN */
    private String mrn;

    /** 来源唯一键（order: order._id 或 mrn+orderName+orderTime 组合键；param: 源 bedside._id） */
    private String sourceKey;

    /** param 同步的目标时间点 */
    private Date targetTimePoint;

    /** 生成的 bedside 记录 ID */
    private String generatedBedsideId;

    /** 执行结果：SUCCESS / SKIP / FAIL */
    private String result;

    /** 附加信息（跳过原因 / 错误信息） */
    private String message;

    /** 同步执行时间 */
    private Date syncTime;

    /** ===== 同步类型常量 ===== */
    public static final String TYPE_PARAM = "PARAM定时";
    public static final String TYPE_ORDER = "ORDER医嘱";

    /** ===== 执行结果常量 ===== */
    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_SKIP = "SKIP";
    public static final String RESULT_FAIL = "FAIL";
}
