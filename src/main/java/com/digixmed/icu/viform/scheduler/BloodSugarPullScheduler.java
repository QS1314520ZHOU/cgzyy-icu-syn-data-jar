package com.digixmed.icu.viform.scheduler;

import com.digixmed.icu.viform.config.BloodSugarPullProperties;
import com.digixmed.icu.viform.service.BloodSugarPullSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 血糖拉取调度器。
 *
 * <p>每 5 分钟从人大金仓视图 v_blood_for_zzxt 拉取在科患者血糖数据写入 bloodSugar 集合。
 * 可通过 {@code bloodsugar-pull.enabled=false} 关闭。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "bloodsugar-pull.enabled", havingValue = "true")
public class BloodSugarPullScheduler {

    private final BloodSugarPullProperties properties;
    private final BloodSugarPullSyncService bloodSugarPullSyncService;

    /**
     * 定时扫描：fixedDelay，上一轮完成后等待 interval 再执行下一轮。
     */
    @Scheduled(
            fixedDelayString = "${bloodsugar-pull.scan-interval-ms:300000}",
            initialDelayString = "${bloodsugar-pull.initial-delay-ms:60000}")
    public void scheduledSync() {
        if (!properties.isEnabled()) {
            log.debug("[BloodSugarPull] 调度器已禁用 (enabled=false)，跳过");
            return;
        }
        log.info("[BloodSugarPull] 定时扫描触发...");
        try {
            BloodSugarPullSyncService.SyncResult result = bloodSugarPullSyncService.sync();
            log.info("[BloodSugarPull] 定时扫描完成: {}", result);
        } catch (Exception e) {
            log.error("[BloodSugarPull] 定时扫描异常", e);
        }
    }

    /**
     * 应用启动后补偿执行一次（24h 回溯窗口天然覆盖停机期）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void compensateOnStartup() {
        if (!properties.isEnabled()) {
            log.info("[BloodSugarPull] 调度器已禁用，跳过启动补偿");
            return;
        }
        log.info("[BloodSugarPull] 启动补偿执行一次同步...");
        try {
            BloodSugarPullSyncService.SyncResult result = bloodSugarPullSyncService.sync();
            log.info("[BloodSugarPull] 启动补偿完成: {}", result);
        } catch (Exception e) {
            log.error("[BloodSugarPull] 启动补偿异常", e);
        }
    }
}
