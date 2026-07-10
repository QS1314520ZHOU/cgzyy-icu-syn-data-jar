package com.digixmed.icu.viform.entity;

import lombok.Data;

import java.util.Date;

/**
 * 患者装床历史记录（占位实体）。
 *
 * <p>TODO: 字段口径请根据 patient.patBedHistories 的真实结构补全。</p>
 */
@Data
public class PatBedHistorie {
    /** 床位号 */
    private String bed;
    /** 变更时间 */
    private Date time;
}
