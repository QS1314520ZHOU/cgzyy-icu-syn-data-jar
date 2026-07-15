package com.digixmed.icu.viform.scheduler;

import com.digixmed.icu.viform.config.BloodSugarSyncProperties;
import com.digixmed.icu.viform.service.BloodSugarSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 血糖同步调度器。
 *
 * <p>按 {@code bloodsugar-sync.scan-interval-ms} 周期（默认 5 分钟）
 * 调用 {@link BloodSugarSyncService#sync()}。</p>
 *
 * <p>可通过 {@code bloodsugar-sync.enabled=false} 关闭。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BloodSugarSyncScheduler {

    private final BloodSugarSyncProperties bloodSugarSyncProperties;
    private final BloodSugarSyncService bloodSugarSyncService;

    /**
     * 定时扫描：按 fixedDelayString 从 YAML 读取间隔。
     */
    @Scheduled(fixedDelayString = "${bloodsugar-sync.scan-interval-ms:300000}")
    public void scheduledSync() {
        if (!bloodSugarSyncProperties.isEnabled()) {
            log.debug("[BloodSugarSync] 调度器已禁用，跳过");
            return;
        }
        log.info("[BloodSugarSync] 定时扫描触发...");
        try {
            Map<String, Integer> stats = bloodSugarSyncService.sync();
            log.info("[BloodSugarSync] 定时扫描完成: {}", stats);
        } catch (Exception e) {
            log.error("[BloodSugarSync] 定时扫描异常", e);
        }
    }

    /**
     * 应用启动补偿：启动后立即执行一次。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void compensateOnStartup() {
        if (!bloodSugarSyncProperties.isEnabled()) {
            log.info("[BloodSugarSync] 调度器已禁用，跳过启动补偿");
            return;
        }
        log.info("[BloodSugarSync] 启动补偿执行一次同步...");
        try {
            Map<String, Integer> stats = bloodSugarSyncService.sync();
            log.info("[BloodSugarSync] 启动补偿完成: {}", stats);
        } catch (Exception e) {
            log.error("[BloodSugarSync] 启动补偿异常", e);
        }
    }
}
