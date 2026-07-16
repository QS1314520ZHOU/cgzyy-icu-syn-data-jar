package com.digixmed.icu.viform.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * SmartCare 库 account 集合实体 —— 用户/护士账号。
 *
 * <p>存量文档 _class：
 * {@code com.digixmed.icu.smartcare.database.entitys.accout.Account}</p>
 *
 * <p>用于根据护士姓名（trueName）匹配 examinerId。</p>
 */
@Data
@Document("account")
public class Account {

    /** MongoDB 主键 (_id) */
    @Id
    private String id;

    /** 护士真实姓名（匹配用，存量字段名 trueName） */
    private String trueName;

    /** 职业/角色，如 "Nurse" */
    private String profession;

    /** 用户名/登录名 */
    private String username;
}
