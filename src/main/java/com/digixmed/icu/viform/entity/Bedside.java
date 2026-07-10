package com.digixmed.icu.viform.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.List;

/**
 * bedside 集合实体。
 *
 * <p>示例文档：</p>
 * <pre>
 * {
 *   "_id": ObjectId("..."),
 *   "pid": "6a3a2bc0212a5d41edabada3",   // 对应 patient._id
 *   "code": "param_亚低温治疗",
 *   "time": ISODate("2026-07-09T04:00:00.000Z"),
 *   "strVal": "结束",
 *   "valid": true,
 *   "history": [ ... ],
 *   "editUser": "...",
 *   "editTime": ISODate("..."),
 *   "_class": "com.digixmed.icu.smartcare.database.entitys.bedside.Bedside",
 *   "fVal": "1",
 *   "remark": "1"
 * }
 * </pre>
 *
 * <p>说明：Mongo 文档中的 {@code _class} 字段由 Spring Data 自动处理，无需在实体中声明。</p>
 */
@Data
@Document("bedside")
public class Bedside {

    /** MongoDB 主键 (_id) */
    @Id
    private String id;

    /** 关联的患者 ID，对应 patient._id */
    private String pid;

    /** 业务编码，如 param_亚低温治疗 */
    private String code;

    /** 记录时间 */
    private Date time;

    /** 字符串值，如 "结束" */
    private String strVal;

    /** 备用值（示例中为字符串 "1"） */
    private String fVal;

    /** 是否有效 */
    private Boolean valid;

    /** 历史变更记录 */
    private List<BedsideHistory> history;

    /** 最后编辑人账号 ID */
    private String editUser;

    /** 最后编辑时间 */
    private Date editTime;

    /** 备注 */
    private String remark;
}
