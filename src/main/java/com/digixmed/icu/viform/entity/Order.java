package com.digixmed.icu.viform.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

/**
 * DataCenter 库 VI_ICU_ZYYZ 集合实体 —— 医嘱数据。
 *
 * <p>用于医嘱驱动生成 bedside 记录（param_biSiLiquid 鼻饲液）。</p>
 */
@Data
@Document("VI_ICU_ZYYZ")
public class Order {

    /** MongoDB 主键 (_id) */
    @Id
    private String id;

    /** 患者 MRN，关联 patient.mrn */
    private String mrn;

    /** 医嘱名称，如 "禁食禁水" / "流质" */
    private String orderName;

    /** 医嘱下达/生效时间 */
    private Date orderTime;

    /** 医嘱状态，如 "在执行" / "已停止" */
    private String status;
}
