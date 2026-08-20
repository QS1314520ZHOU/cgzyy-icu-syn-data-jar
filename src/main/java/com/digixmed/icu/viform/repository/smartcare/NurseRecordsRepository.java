package com.digixmed.icu.viform.repository.smartcare;

import com.digixmed.icu.viform.entity.NurseRecords;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Date;
import java.util.List;

/**
 * 护理记录仓库（SmartCare 库）。
 */
public interface NurseRecordsRepository extends MongoRepository<NurseRecords, String> {

    /**
     * 查询指定患者在指定时间范围内的自动同步护理记录。
     */
    List<NurseRecords> findByPidAndAutoSynTrueAndTimeBetween(String pid, Date start, Date end);
}
