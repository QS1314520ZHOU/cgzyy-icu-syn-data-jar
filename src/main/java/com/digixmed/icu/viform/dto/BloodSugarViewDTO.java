package com.digixmed.icu.viform.dto;

import lombok.Data;

import java.util.Date;

/**
 * 人大金仓视图 {@code np_nis_cqchonggang.v_blood_for_zzxt} 行 DTO。
 *
 * <p>7 列：姓名 / 住院号 / 录入时间 / 类型 / 数值 / 备注 / 签名。</p>
 */
@Data
public class BloodSugarViewDTO {

    /** 姓名（仅日志旁证，不落库） */
    private String name;

    /** 住院号（10 位定长字符串，含前导 0，禁转数字） */
    private String inhosNo;

    /** 录入时间（墙钟时间，已按 Asia/Shanghai 解释为瞬时） */
    private Date recordTime;

    /** 类型（Q4h / Q2h / 随机 / 睡前 等，原样保留） */
    private String type;

    /** 数值（String，可为 "Hi" 等仪器标记值，原样保留） */
    private String value;

    /** 备注 */
    private String note;

    /** 签名（护士姓名） */
    private String signer;
}
