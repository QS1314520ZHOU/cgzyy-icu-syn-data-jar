package com.digixmed.icu.viform.schedule;

import com.digixmed.icu.viform.service.TransferScoreSyncService;
import com.digixmed.icu.viform.service.TransferScoreSyncService.SyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 危重患者转运评分数据同步定时任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "tube-nursing-sync.enabled", havingValue = "true", matchIfMissing = false)
public class TransferScoreSyncScheduler {

    private final TransferScoreSyncService transferScoreSyncService;

    @Scheduled(fixedDelayString = "${tube-nursing-sync.scan-interval-ms:60000}",
            initialDelayString = "${tube-nursing-sync.initial-delay-ms:60000}")
    public void syncTransferScoreRecords() {
        try {
            log.info("[TransferScoreSyncScheduler] 开始同步转运评分记录...");
            SyncResult result = transferScoreSyncService.syncAllAdmittedPatients();
            log.info("[TransferScoreSyncScheduler] 同步完成 - 患者:{} 新增:{} 跳过:{} 更新:{} 失败:{}",
                    result.totalPatients, result.syncedRecords, result.skippedRecords,
                    result.updatedRecords, result.failedRecords);
        } catch (Exception e) {
            log.error("[TransferScoreSyncScheduler] 同步异常", e);
        }
    }
}
