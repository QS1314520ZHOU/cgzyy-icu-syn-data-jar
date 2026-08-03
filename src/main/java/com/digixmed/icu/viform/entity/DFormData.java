package com.digixmed.icu.viform.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * SmartCare 库 dFormData 集合实体 —— 入院/入科护理评估单。
 *
 * <p>存量文档 _class：
 * {@code com.digixmed.icu.smartcare.database.entitys.dFormData.DFormData}</p>
 */
@Data
@Document("dFormData")
@CompoundIndexes({
        @CompoundIndex(name = "idx_form_pid_status",
                def = "{'pid': 1, 'formCode': 1, 'status': 1}"),
        @CompoundIndex(name = "idx_form_status_code",
                def = "{'status': 1, 'formCode': 1}")
})
public class DFormData {

    /** SmartCare 原始 _class 值（创建新表单时必须使用此值） */
    public static final String D_FORM_DATA_CLASS =
            "com.digixmed.icu.smartcare.database.entitys.dFormData.DFormData";

    /** MongoDB 主键 (_id) */
    @Id
    private String id;

    /** 患者 ID，对应 patient._id */
    private String pid;

    /** 表单编码 */
    private String formCode;

    /** 表单状态：valid / invalid */
    private String status;

    /** 表单字段数据列表 */
    private List<DFormFieldData> fieldDataList = new ArrayList<>();

    /** 最后编辑时间 */
    private Date editTime;
}
