package com.digixmed.icu.viform.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.List;

/**
 * patient 集合实体（来自用户提供的 Patient.java，仅调整了包路径到 entity 子包）。
 */
@Data
@Document("patient")
public class Patient {
    /**
     * MongoDB 主键 (_id)
     * 注意：Spring Data MongoDB 会自动将 _id 映射到 @Id 字段上
     */
    @Id
    private String id;

    /** HIS 患者 ID */
    private String hisPid;

    private String mrn;

    /** 记录创建时间 */
    private Date createdTime;

    /** 入院时间 */
    private Date admissionTime;

    /** 患者姓名 */
    private String name;

    /** 出生日期 */
    private Date birthday;

    /** 性别 (Male/Female) */
    private String gender;

    /** 过敏史记录列表 (JSON中为空数组，此处用 Object 占位，建议后续替换为具体实体) */
    private List<Object> allergicRecordList;

    /** 住院天数/时长 */
    private String hospitalTime;

    /** HIS 床位号 */
    private String hisBed;

    /** 分配床位时间 */
    private Date bedTime;
    /** 装床时间记录 */
    private List<PatBedHistorie> patBedHistories;

    /** 患者状态 (如: admitted, discharged) */
    private String status;

    /** ICU 转入时间 */
    private Date icuAdmissionTime;

    /** HIS 入院时间 */
    private Date hisAdmissionTime;

    /** HIS 入院时间历史记录列表 */
    private List<Date> hisAdmissionTimeList;

    /** 收治来源科室 */
    private String admissionSource;

    /** 收治计划 */
    private String admissionPlan;

    /** 临床诊断时间 */
    private Date clinicalDiagnosisTime;
    /** 临床诊断 */
    private String clinicalDiagnosis;

    private List<String> clinicalDiagnosisCodeList;

    /** 医保类型 */
    private String insuranceType;

    /** 所在科室名称 */
    private String dept;

    /** 科室编码 */
    private String deptCode;

    /** 主诉 */
    private String chiefComplaint;

    /** 主管医生 ID */
    private String bedDoctorId;

    /** 入院类型 */
    private String admissionType;

    /** 出院诊断 ICD 编码列表 */
    private List<String> dischargedDiagnosisIcd;

    /** 是否为合并患者 */
    private Boolean merge;

    /** 是否允许返回 */
    private Boolean allowReturn;

    /** 是否满足收治条件 */
    private Boolean meetAdmitRequirement;

    /** 是否满足出科条件 */
    private Boolean meetDischargeRequirement;

    /** 备用科室编码 (可能是双院区或历史遗留字段) */
    private String deptCode2;

    /** 费用记录列表 */
    private List<Object> expenseRecordList;

    private Date deathTime;

    private String dischargedType;

    private String dischargedDepartment;
    private String dischargedDepartmentCode;
    private Date icuDischargeTime;
    private List<PatientOperations> patientOperations;
}
