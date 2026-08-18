package com.digixmed.icu.viform.scheduler;

import com.digixmed.icu.viform.config.FirstAdmissionAssessmentSyncProperties;
import com.digixmed.icu.viform.service.FirstAdmissionAssessmentSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 首次入科评估同步调度器。
 *
 * <p>以 3 分钟为固定轮询周期（{@code first-assessment-sync.scan-interval-ms}），
 * 由同步服务根据患者入科时间动态决定是否处理：</p>
 * <ul>
 *   <li>入科 0~1 小时（密集期）：每次都同步</li>
 *   <li>入科 1~24 小时（稳定期）：每小时同步一次</li>
 *   <li>入科超过 24 小时（过期）：不再同步</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FirstAdmissionAssessmentSyncScheduler {

    private final FirstAdmissionAssessmentSyncProperties properties;
    private final FirstAdmissionAssessmentSyncService syncService;

    @Scheduled(fixedDelayString = "${first-assessment-sync.scan-interval-ms:180000}")
    public void scheduledSync() {
        if (!properties.isEnabled()) {
            log.debug("[FirstAssessmentSync] 调度器已禁用，跳过");
            return;
        }
        log.info("[FirstAssessmentSync] 定时扫描触发...");
        try {
            FirstAdmissionAssessmentSyncService.SyncResult result = syncService.syncAllAdmittedPatients();
            log.info("[FirstAssessmentSync] 定时扫描完成: {}", result.toMap());
        } catch (Exception e) {
            log.error("[FirstAssessmentSync] 定时扫描异常", e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void compensateOnStartup() {
        if (!properties.isEnabled()) {
            log.info("[FirstAssessmentSync] 调度器已禁用，跳过启动补偿");
            return;
        }
        log.info("[FirstAssessmentSync] 启动补偿执行一次同步...");
        try {
            FirstAdmissionAssessmentSyncService.SyncResult result = syncService.syncAllAdmittedPatients();
            log.info("[FirstAssessmentSync] 启动补偿完成: {}", result.toMap());
        } catch (Exception e) {
            log.error("[FirstAssessmentSync] 启动补偿异常", e);
        }
    }
}
