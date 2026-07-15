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
 * <p>说明：Mongo 文档中的 {@code _class} 字段由 Spring Data 自动处理。<b>自动同步生成的 bedside 必须使用
 * {@link #BEDSIDE_CLASS} 作为 {@code _class} 值</b>（SmartCare 原类名），通过
 * {@code setOnInsert("_class", BEDSIDE_CLASS)} 写入。</p>
 */
@Data
@Document("bedside")
public class Bedside {

    /**
     * 自动同步生成的 bedside 固定 _class 值（SmartCare 库原类名）。
     *
     * <p>Spring Data 默认会写当前项目类名 {@code com.digixmed.icu.viform.entity.Bedside}，
     * 但与 SmartCare 主程序期望的类名不一致，因此同步时统一用此常量做 setOnInsert。</p>
     */
    public static final String BEDSIDE_CLASS =
            "com.digixmed.icu.smartcare.database.entitys.bedside.Bedside";

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

    /**
     * 业务备注（由主程序或源数据控制）。
     *
     * <p>自动同步行为：</p>
     * <ul>
     *   <li>Param/源联动同步：跟随源记录 remark，源变目标跟着变</li>
     *   <li>血糖/医嘱同步：不写此字段，不覆盖已有值</li>
     * </ul>
     */
    private String remark;

    /**
     * 自动同步备注（本同步 jar 专用，与主程序 remark 隔离）。
     *
     * <p>由同步程序固定写入对应的同步说明文本，不从源复制：</p>
     * <ul>
     *   <li>Param 定时 → "系统定时同步"</li>
     *   <li>Source 联动 → "源联动同步"</li>
     *   <li>血糖同步 → "血糖同步 | detectionProject=… | specimenSource=…"</li>
     *   <li>医嘱同步 → "医嘱同步生成"</li>
     * </ul>
     */
    private String synRemark;
}
