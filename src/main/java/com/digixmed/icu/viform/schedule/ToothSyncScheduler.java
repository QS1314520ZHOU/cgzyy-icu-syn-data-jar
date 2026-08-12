package com.digixmed.icu.viform.schedule;

import com.digixmed.icu.viform.service.ToothSyncService;
import com.digixmed.icu.viform.service.ToothSyncService.SyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 牙齿管理数据同步定时任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "tube-nursing-sync.enabled", havingValue = "true", matchIfMissing = false)
public class ToothSyncScheduler {

    private final ToothSyncService toothSyncService;

    @Scheduled(fixedDelayString = "${tube-nursing-sync.scan-interval-ms:60000}",
            initialDelayString = "${tube-nursing-sync.initial-delay-ms:60000}")
    public void syncToothRecords() {
        try {
            log.info("[ToothSyncScheduler] 开始同步牙齿管理记录...");
            SyncResult result = toothSyncService.syncAllAdmittedPatients();
            log.info("[ToothSyncScheduler] 同步完成 - 患者:{} 新增:{} 跳过:{} 更新:{} 失败:{}",
                    result.totalPatients, result.syncedRecords, result.skippedRecords,
                    result.updatedRecords, result.failedRecords);
        } catch (Exception e) {
            log.error("[ToothSyncScheduler] 同步异常", e);
        }
    }
}
