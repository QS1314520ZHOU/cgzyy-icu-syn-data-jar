package com.digixmed.icu.viform.scheduler;

import com.digixmed.icu.viform.config.SourceSyncProperties;
import com.digixmed.icu.viform.service.SourceDrivenSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 源 code 联动同步调度器。
 *
 * <p>按 {@code source-sync.scan-interval-ms} 周期（默认 5 分钟）调用
 * {@link SourceDrivenSyncService#syncAll()}。</p>
 *
 * <p>可通过 {@code source-sync.enabled=false} 关闭。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SourceSyncScheduler {

    private final SourceSyncProperties sourceSyncProperties;
    private final SourceDrivenSyncService sourceDrivenSyncService;

    /**
     * 定时扫描：按 fixedDelayString 从 YAML 读取间隔。
     *
     * <p>fixedDelay：上一次执行完成后等待 interval 再执行下一次，
     * 避免任务堆积。</p>
     */
    @Scheduled(fixedDelayString = "${source-sync.scan-interval-ms:300000}")
    public void scheduledSync() {
        if (!sourceSyncProperties.isEnabled()) {
            log.debug("[SourceSync] 调度器已禁用 (enabled=false)，跳过");
            return;
        }
        log.info("[SourceSync] 定时扫描触发...");
        try {
            Map<String, Integer> stats = sourceDrivenSyncService.syncAll();
            log.info("[SourceSync] 定时扫描完成: {}", stats);
        } catch (Exception e) {
            log.error("[SourceSync] 定时扫描异常", e);
        }
    }

    /**
     * 应用启动后补偿执行一次（可选：生产环境首次启动时立即同步）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void compensateOnStartup() {
        if (!sourceSyncProperties.isEnabled()) {
            log.info("[SourceSync] 调度器已禁用，跳过启动补偿");
            return;
        }
        log.info("[SourceSync] 启动补偿执行一次同步...");
        try {
            Map<String, Integer> stats = sourceDrivenSyncService.syncAll();
            log.info("[SourceSync] 启动补偿完成: {}", stats);
        } catch (Exception e) {
            log.error("[SourceSync] 启动补偿异常", e);
        }
    }
}
