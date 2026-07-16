package com.digixmed.icu.viform.repository.smartcare;

import com.digixmed.icu.viform.entity.Patient;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * patient 集合仓库（SmartCare 库）。
 */
public interface PatientRepository extends MongoRepository<Patient, String> {

    /**
     * 按状态查询患者，例如 status = "admitted"（在院）。
     */
    List<Patient> findByStatus(String status);
    /**
     * 按 MRN（病历号）查询患者（patNo → mrn 匹配）。
     */
    Patient findByMrn(String mrn);
}
