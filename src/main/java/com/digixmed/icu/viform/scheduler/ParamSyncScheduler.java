package com.digixmed.icu.viform.scheduler;

import com.digixmed.icu.viform.config.SyncGroupsProperties;
import com.digixmed.icu.viform.config.SyncGroupsProperties.SyncGroup;
import com.digixmed.icu.viform.service.ParamTimedSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.Date;

/**
 * bedside 定时同步调度器。
 *
 * <p>每分钟轮询：若当前时间 == (某分组 timePoint - advanceMinutes)，则触发对应分组的同步。</p>
 *
 * <p>触发时机示例（advance-minutes=5）：</p>
 * <ul>
 *   <li>09:55 触发 10:00 同步</li>
 *   <li>13:55 触发 14:00 同步</li>
 *   <li>23:55 触发次日 00:00 同步（跨天处理）</li>
 * </ul>
 *
 * <p>启动补偿：若应用在触发窗口（triggerTime ~ timePoint+5min）内启动，自动补一次同步。
 * 幂等由 {@link ParamTimedSyncService} 内部保证。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParamSyncScheduler {

    private final SyncGroupsProperties syncGroupsProperties;
    private final ParamTimedSyncService paramTimedSyncService;

    /** 服务器时区（GMT+8） */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 补偿窗口：timePoint 之后多久内仍可补偿（分钟） */
    private static final int COMPENSATE_GRACE_MINUTES = 10;

    // ==================== 定时调度（每分钟第 0 秒） ====================

    @Scheduled(cron = "0 * * * * *")
    public void checkAndTrigger() {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        LocalTime nowTime = now.toLocalTime();
        int advance = syncGroupsProperties.getAdvanceMinutes();

        for (SyncGroup group : syncGroupsProperties.getGroups()) {
            for (String timeStr : group.getTimes()) {
                LocalTime timePoint = LocalTime.parse(timeStr);
                LocalTime triggerTime = timePoint.minusMinutes(advance);

                // 精确匹配当前分钟 == 触发分钟
                if (nowTime.getHour() != triggerTime.getHour()
                        || nowTime.getMinute() != triggerTime.getMinute()) {
                    continue;
                }

                // 计算目标日期
                Date targetTime = computeTargetDate(now, timePoint, triggerTime);
                log.info("[Scheduler] 触发同步 group={}, targetTime={}",
                        group.getName(), timeStr);

                try {
                    paramTimedSyncService.sync(group, targetTime);
                } catch (Exception e) {
                    log.error("[Scheduler] 同步异常 group={}, targetTime={}",
                            group.getName(), timeStr, e);
                }
            }
        }
    }

    // ==================== 启动补偿 ====================

    /**
     * 应用启动后补偿：若启动时已过某时间点的触发时刻，且仍在补偿窗口内，
     * 则立即执行一次同步（场景：09:58 重启，错过了 09:55→10:00 的触发）。
     *
     * <p>不补偿距离当前时间太久的历史时间点。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void compensateOnStartup() {
        log.info("[Scheduler] 启动补偿检查...");
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        LocalTime nowTime = now.toLocalTime();
        int advance = syncGroupsProperties.getAdvanceMinutes();

        int compensated = 0;
        for (SyncGroup group : syncGroupsProperties.getGroups()) {
            for (String timeStr : group.getTimes()) {
                LocalTime timePoint = LocalTime.parse(timeStr);
                LocalTime triggerTime = timePoint.minusMinutes(advance);

                // 如果本分钟恰好是触发时刻 → 跳过，交给 checkAndTrigger
                if (nowTime.getHour() == triggerTime.getHour()
                        && nowTime.getMinute() == triggerTime.getMinute()) {
                    continue;
                }

                // 判断是否在补偿窗口内
                if (!isInCompensateWindow(nowTime, timePoint, triggerTime)) {
                    continue;
                }

                // 计算目标日期（处理跨天）
                Date targetTime = computeTargetDate(now, timePoint, triggerTime);
                log.info("[Scheduler] 补偿执行 group={}, targetTime={}",
                        group.getName(), timeStr);

                try {
                    paramTimedSyncService.sync(group, targetTime);
                    compensated++;
                } catch (Exception e) {
                    log.error("[Scheduler] 补偿同步异常 group={}, targetTime={}",
                            group.getName(), timeStr, e);
                }
            }
        }
        if (compensated > 0) {
            log.info("[Scheduler] 启动补偿完成，补偿了 {} 个时间点", compensated);
        } else {
            log.info("[Scheduler] 无需补偿调度");
        }
    }

    // ==================== 内部工具方法 ====================

    /**
     * 计算目标时间点的 Date。
     *
     * <p>跨天情况：triggerTime(23:55) > timePoint(00:00)
     *   若当前在 23:55-23:59 → 目标是"明天 00:00"
     *   若当前在 00:00-00:xx → 目标是"今天 00:00"</p>
     */
    private Date computeTargetDate(ZonedDateTime now, LocalTime timePoint, LocalTime triggerTime) {
        LocalDate targetDate;
        if (triggerTime.isAfter(timePoint)) {
            // 跨天
            LocalTime nowTime = now.toLocalTime();
            if (!nowTime.isBefore(triggerTime)) {
                // 当前在 trigger(23:55) 之后 → 目标是明天
                targetDate = now.toLocalDate().plusDays(1);
            } else {
                // 当前在 timePoint(00:00) 附近 → 目标是今天
                targetDate = now.toLocalDate();
            }
        } else {
            targetDate = now.toLocalDate();
        }
        return Date.from(ZonedDateTime.of(targetDate, timePoint, ZONE).toInstant());
    }

    /**
     * 判断当前时间是否在补偿窗口内。
     *
     * <p>窗口定义：[triggerTime, timePoint + COMPENSATE_GRACE_MINUTES]</p>
     *
     * <p>跨天示例（triggerTime=23:55, timePoint=00:00）：
     *   当前 23:56 → 在窗口内（>= 23:55）
     *   当前 00:03 → 在窗口内（< 00:10）
     *   当前 01:00 → 不在窗口内</p>
     */
    private boolean isInCompensateWindow(LocalTime nowTime, LocalTime timePoint, LocalTime triggerTime) {
        if (triggerTime.isBefore(timePoint)) {
            // 正常情况：09:55 触发 10:00
            // 窗口：[09:55, 10:10]
            return !nowTime.isBefore(triggerTime)
                    && !nowTime.isAfter(timePoint.plusMinutes(COMPENSATE_GRACE_MINUTES));
        } else {
            // 跨天情况：23:55 触发次日 00:00
            // 窗口：[23:55, 23:59] ∪ [00:00, 00:10]
            return !nowTime.isBefore(triggerTime)
                    || !nowTime.isAfter(timePoint.plusMinutes(COMPENSATE_GRACE_MINUTES));
        }
    }
}
