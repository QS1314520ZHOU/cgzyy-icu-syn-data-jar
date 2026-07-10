package com.digixmed.icu.viform.repository.smartcare;

import com.digixmed.icu.viform.entity.BedsideSyncLog;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Date;
import java.util.Optional;

/**
 * bedside_sync_log 集合仓库（SmartCare 库）。
 *
 * <p>承担同步幂等去重职责。</p>
 */
public interface BedsideSyncLogRepository extends MongoRepository<BedsideSyncLog, String> {

    /**
     * 按 sourceKey 查询日志：用于 Order 医嘱同步幂等。
     */
    Optional<BedsideSyncLog> findBySourceKey(String sourceKey);

    /**
     * 按 (pid, code, targetTimePoint) 查询日志：用于 Param 定时同步幂等。
     */
    Optional<BedsideSyncLog> findByPidAndCodeAndTargetTimePoint(String pid, String code, Date targetTimePoint);
}
