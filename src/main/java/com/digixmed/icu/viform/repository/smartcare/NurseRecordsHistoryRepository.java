package com.digixmed.icu.viform.repository.smartcare;

import com.digixmed.icu.viform.entity.NurseRecordsHistory;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Date;
import java.util.List;

/**
 * 护理记录同步历史仓库（SmartCare 库）。
 */
public interface NurseRecordsHistoryRepository extends MongoRepository<NurseRecordsHistory, String> {

    /**
     * 按患者 ID 列表查询同步历史。
     */
    List<NurseRecordsHistory> findByPidIn(List<String> pids);

    /**
     * 按患者 ID 列表和同步类型查询同步历史。
     */
    List<NurseRecordsHistory> findByPidInAndSyncType(List<String> pids, String syncType);

    /**
     * 按管道执行记录 ID、班次类型和护理时间查询同步历史。
     */
    NurseRecordsHistory findByTubeExeIdAndShiftTypeAndTubeRecordTime(
            String tubeExeId, String shiftType, Date tubeRecordTime);
}
