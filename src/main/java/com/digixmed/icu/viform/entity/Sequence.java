package com.digixmed.icu.viform.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * MongoDB 自增序列集合。
 *
 * <p>文档结构：{@code { _id: "bloodSugar", seq: 1 }}</p>
 */
@Data
@Document("sequence")
public class Sequence {

    /** 序列名称（如 "bloodSugar"） */
    @Id
    private String id;

    /** 当前序列值 */
    private Long seq;
}
