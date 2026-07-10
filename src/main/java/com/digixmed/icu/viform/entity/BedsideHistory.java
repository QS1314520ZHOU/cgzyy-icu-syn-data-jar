package com.digixmed.icu.viform.entity;

import lombok.Data;

import java.util.Date;

/**
 * bedside.history 内嵌历史记录项。
 */
@Data
public class BedsideHistory {
    /** 变更时间 */
    private Date time;
    /** 操作账号 ID */
    private String accountId;
    /** 描述，如 "复温"（可能不存在） */
    private String desc;
}
