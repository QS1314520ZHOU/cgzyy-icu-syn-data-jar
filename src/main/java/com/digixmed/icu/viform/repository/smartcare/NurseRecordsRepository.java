package com.digixmed.icu.viform.repository.smartcare;

import com.digixmed.icu.viform.entity.NurseRecords;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * 护理记录仓库（SmartCare 库）。
 */
public interface NurseRecordsRepository extends MongoRepository<NurseRecords, String> {
}
