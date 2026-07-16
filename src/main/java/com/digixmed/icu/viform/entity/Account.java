package com.digixmed.icu.viform.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * SmartCare 库 account 集合实体 —— 用户/护士账号。
 *
 * <p>用于根据护士姓名匹配 examinerId，字段按需扩展。</p>
 */
@Data
@Document("account")
public class Account {

    /** MongoDB 主键 (_id) */
    @Id
    private String id;

    /** 用户姓名（护士姓名） */
    private String name;

    /** 账号/登录名（TODO: 确认 account 集合中姓名字段的实际名称，可能是 name / userName / displayName） */
    private String userName;
}
