package com.digixmed.icu.viform.entity;

import lombok.Data;

import java.util.Date;

/**
 * 患者手术记录（占位实体）。
 *
 * <p>TODO: 字段口径请根据 patient.patientOperations 的真实结构补全。</p>
 */
@Data
public class PatientOperations {
    /** 手术名称 */
    private String name;
    /** 手术时间 */
    private Date time;
}
