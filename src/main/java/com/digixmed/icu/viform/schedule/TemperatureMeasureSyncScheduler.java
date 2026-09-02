package com.digixmed.icu.viform.schedule;

import com.digixmed.icu.viform.service.TemperatureMeasureSyncService;
import com.digixmed.icu.viform.service.TemperatureMeasureSyncService.SyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 降温/升温措施同步定时任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "tube-nursing-sync.enabled", havingValue = "true", matchIfMissing = false)
public class TemperatureMeasureSyncScheduler {

    private final TemperatureMeasureSyncService temperatureMeasureSyncService;

    @Scheduled(fixedDelayString = "${tube-nursing-sync.scan-interval-ms:300000}",
            initialDelayString = "${tube-nursing-sync.initial-delay-ms:60000}")
    public void syncTemperatureMeasureRecords() {
        try {
            log.info("[TempMeasureScheduler] 开始同步降温/升温措施记录...");
            SyncResult result = temperatureMeasureSyncService.syncAllAdmittedPatients();
            log.info("[TempMeasureScheduler] 同步完成 - 患者:{} 新增:{} 跳过:{} 更新:{} 失败:{}",
                    result.totalPatients, result.syncedRecords, result.skippedRecords,
                    result.updatedRecords, result.failedRecords);
        } catch (Exception e) {
            log.error("[TempMeasureScheduler] 同步异常", e);
        }
    }
}
