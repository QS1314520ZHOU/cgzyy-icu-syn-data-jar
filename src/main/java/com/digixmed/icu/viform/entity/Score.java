package com.digixmed.icu.viform.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.Map;

/**
 * SmartCare 库 score 集合实体 —— 评估评分（跌倒/坠床等）。
 *
 * <p>本需求主要使用 scoreType = "patientFallDangerLJRMYY"。</p>
 */
@Data
@Document("score")
public class Score {

    /** MongoDB 主键 (_id) */
    @Id
    private String id;

    /** 患者 ID，对应 patient._id */
    private String pid;

    /** 评分类型，如 "patientFallDangerLJRMYY" */
    private String scoreType;

    /** 是否有效 */
    private Boolean valid;

    /** 评分时间 */
    private Date time;

    /** 最后编辑时间 */
    private Date editTime;

    /** 总分（可能为 Number 或 String，需兼容） */
    private Object total;

    /** 评分结论 */
    private String conclusion;

    /**
     * 跌倒/坠床危险因素（V2 版本）。
     * <p>包含：hunmiOntanhaun, preHospitalization, sylzys, age,
     * thisHospitalization, exist, sixHours,
     * fallHistory, otherDiagnosis, useWalkTool,
     * intravenousInjection, walk, mentality 等字段。</p>
     */
    private Map<String, Object> patientFallDangerFactorV2;
}
