package com.digixmed.icu.viform.schedule;

import com.digixmed.icu.viform.service.BedsideSyncService;
import com.digixmed.icu.viform.service.BedsideSyncService.SyncResult;
import com.digixmed.icu.viform.service.TubeNursingSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 统一护理记录同步调度器。
 *
 * <p>替代原来的 AssessmentScoreSync、SkinCareSync、ToothSync、
 * TemperatureMeasureSync、TransferScoreSync、VentilatorSync、
 * TubeNursingSync 七个独立调度器，确保同一时间点的数据合并到同一条护理记录。</p>
 *
 * <p>执行顺序：</p>
 * <ol>
 *   <li>BedsideSync（评估评分/皮肤护理/牙齿/体温/转运评分/呼吸机参数）</li>
 *   <li>TubeNursingSync（管道护理）</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "tube-nursing-sync.enabled", havingValue = "true", matchIfMissing = false)
public class NurseRecordSyncScheduler {

    private final BedsideSyncService bedsideSyncService;
    private final TubeNursingSyncService tubeNursingSyncService;

    @Scheduled(fixedDelayString = "${tube-nursing-sync.scan-interval-ms:300000}",
            initialDelayString = "${tube-nursing-sync.initial-delay-ms:60000}")
    public void syncNurseRecords() {
        // 1. bedside 数据（6个syncType 串行处理）
        try {
            log.info("[NurseRecordSync] 开始同步 bedside 数据...");
            SyncResult result = bedsideSyncService.syncAllAdmittedPatients();
            log.info("[NurseRecordSync] bedside 同步完成 - 患者:{} 新增:{} 跳过:{} 更新:{} 失败:{}",
                    result.totalPatients, result.syncedRecords, result.skippedRecords,
                    result.updatedRecords, result.failedRecords);
        } catch (Exception e) {
            log.error("[NurseRecordSync] bedside 同步异常", e);
        }

        // 2. 管道护理数据
        try {
            log.info("[NurseRecordSync] 开始同步管道护理数据...");
            TubeNursingSyncService.SyncResult tubeResult = tubeNursingSyncService.syncAllAdmittedPatients();
            log.info("[NurseRecordSync] 管道同步完成 - 患者:{} 管道:{} 新增:{} 跳过:{} 更新:{} 失败:{}",
                    tubeResult.totalPatients, tubeResult.totalTubes,
                    tubeResult.syncedRecords, tubeResult.skippedRecords,
                    tubeResult.updatedRecords, tubeResult.failedRecords);
        } catch (Exception e) {
            log.error("[NurseRecordSync] 管道同步异常", e);
        }
    }
}
