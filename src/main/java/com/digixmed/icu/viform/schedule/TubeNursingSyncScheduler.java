package com.digixmed.icu.viform.schedule;

import com.digixmed.icu.viform.service.TubeNursingSyncService;
import com.digixmed.icu.viform.service.TubeNursingSyncService.SyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 管道护理记录同步定时任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "tube-nursing-sync.enabled", havingValue = "true", matchIfMissing = false)
public class TubeNursingSyncScheduler {

    private final TubeNursingSyncService tubeNursingSyncService;

    @Scheduled(fixedDelayString = "${tube-nursing-sync.scan-interval-ms:60000}",
            initialDelayString = "${tube-nursing-sync.initial-delay-ms:30000}")
    public void syncTubeNursingRecords() {
        try {
            log.info("[TubeNursingSyncScheduler] 开始同步管道护理记录...");
            SyncResult result = tubeNursingSyncService.syncAllAdmittedPatients();
            log.info("[TubeNursingSyncScheduler] 同步完成 - 患者:{} 管道:{} 新增:{} 跳过:{} 更新:{} 失败:{}",
                    result.totalPatients, result.totalTubes,
                    result.syncedRecords, result.skippedRecords,
                    result.updatedRecords, result.failedRecords);
        } catch (Exception e) {
            log.error("[TubeNursingSyncScheduler] 同步异常", e);
        }
    }
}
