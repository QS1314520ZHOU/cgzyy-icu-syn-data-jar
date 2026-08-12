package com.digixmed.icu.viform.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 账户实体（SmartCare 库 account 集合）。
 */
@Data
@Document("account")
public class Account {

    @Id
    private String id;

    /** 用户名 */
    private String username;

    /** 真实姓名 */
    private String trueName;

    /** 密码 */
    private String password;

    /** 职业 */
    private String profession;

    /** 学历 */
    private String educationLevel;

    /** 科室编码 */
    private String departmentCode;

    /** 性别 */
    private String sex;

    /** 状态 */
    private String valid;
}
