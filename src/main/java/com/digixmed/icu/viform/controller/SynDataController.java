package com.digixmed.icu.viform.controller;

import com.digixmed.icu.viform.config.OrderSyncProperties;
import com.digixmed.icu.viform.config.SyncGroupsProperties;
import com.digixmed.icu.viform.entity.Bedside;
import com.digixmed.icu.viform.service.AdmittedPatientBedsideService;
import com.digixmed.icu.viform.service.BloodSugarSyncService;
import com.digixmed.icu.viform.service.OrderSyncService;
import com.digixmed.icu.viform.service.ParamTimedSyncService;
import com.digixmed.icu.viform.service.SourceDrivenSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 手动触发 / 调试 / 健康检查接口。
 */
@Slf4j
@RestController
@RequestMapping("/syn")
@RequiredArgsConstructor
public class SynDataController {

    private final AdmittedPatientBedsideService service;
    private final ParamTimedSyncService paramTimedSyncService;
    private final OrderSyncService orderSyncService;
    private final SourceDrivenSyncService sourceDrivenSyncService;
    private final BloodSugarSyncService bloodSugarSyncService;
    private final SyncGroupsProperties syncGroupsProperties;
    private final OrderSyncProperties orderSyncProperties;

    /** 健康检查。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        log.debug("[API] GET /syn/health");
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("status", "UP");
        info.put("serverTime", ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        info.put("timezone", syncGroupsProperties.getTimezone());
        info.put("advanceMinutes", syncGroupsProperties.getAdvanceMinutes());

        // 同步分组概况
        List<Map<String, Object>> groupInfos = new ArrayList<>();
        for (var g : syncGroupsProperties.getGroups()) {
            Map<String, Object> gi = new LinkedHashMap<>();
            gi.put("name", g.getName());
            gi.put("description", g.getDescription());
            gi.put("times", g.getTimes());
            gi.put("codeCount", g.getCodes().size());
            groupInfos.add(gi);
        }
        info.put("syncGroups", groupInfos);

        // 医嘱同步概况
        Map<String, Object> orderInfo = new LinkedHashMap<>();
        orderInfo.put("name", orderSyncProperties.getName());
        orderInfo.put("targetCode", orderSyncProperties.getTargetCode());
        orderInfo.put("orderStatus", orderSyncProperties.getOrderStatus());
        orderInfo.put("keywordsCount", orderSyncProperties.getOrderNameKeywords().size());
        info.put("orderSync", orderInfo);

        return info;
    }

    /** 手动触发一次完整处理流程（原有逻辑，向后兼容）。 */
    @PostMapping("/process")
    public Map<String, Object> process() {
        log.info("[API] POST /syn/process - 手动触发策略分发同步");
        long start = System.currentTimeMillis();
        int count = service.process();
        long elapsed = System.currentTimeMillis() - start;
        log.info("[API] POST /syn/process 完成: handled={}, 耗时={}ms", count, elapsed);
        return Map.of("handled", count, "type", "strategy-dispatch", "elapsedMs", elapsed);
    }

    /**
     * 手动触发 param 定时同步（可选择指定分组和目标时间点）。
     *
     * <p>不传参数时，对所有分组的"当前最近时间点"执行一次补偿同步。</p>
     */
    @PostMapping("/param-sync")
    public Map<String, Object> paramSync() {
        log.info("[API] POST /syn/param-sync - 手动触发 param 定时同步");
        long start = System.currentTimeMillis();
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(syncGroupsProperties.getTimezone()));
        LocalTime nowTime = now.toLocalTime();
        int advance = syncGroupsProperties.getAdvanceMinutes();

        List<Map<String, String>> triggered = new ArrayList<>();

        for (var group : syncGroupsProperties.getGroups()) {
            for (String timeStr : group.getTimes()) {
                LocalTime timePoint = LocalTime.parse(timeStr);
                LocalTime triggerTime = timePoint.minusMinutes(advance);

                // 手动触发：只处理"当前时间已过触发时间但未过 timePoint+1min"的时间点
                boolean inWindow;
                if (triggerTime.isBefore(timePoint)) {
                    inWindow = !nowTime.isBefore(triggerTime)
                            && nowTime.isBefore(timePoint.plusMinutes(1));
                } else {
                    inWindow = !nowTime.isBefore(triggerTime)
                            || nowTime.isBefore(timePoint.plusMinutes(1));
                }
                if (!inWindow) continue;

                // 计算目标日期（与 Scheduler 一致）
                LocalDate targetDate = triggerTime.isAfter(timePoint)
                        ? now.toLocalDate().plusDays(1)
                        : now.toLocalDate();

                ZonedDateTime targetZdt = ZonedDateTime.of(targetDate, timePoint,
                        ZoneId.of(syncGroupsProperties.getTimezone()));
                Date targetTime = Date.from(targetZdt.toInstant());

                paramTimedSyncService.sync(group, targetTime);
                triggered.add(Map.of(
                        "group", group.getName(),
                        "timePoint", timeStr,
                        "targetDate", targetDate.toString()
                ));
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        if (triggered.isEmpty()) {
            log.info("[API] POST /syn/param-sync 完成: 无窗口内时间点, 耗时={}ms", elapsed);
            return Map.of("message", "当前不在任何同步窗口内", "triggered", Collections.emptyList());
        }
        log.info("[API] POST /syn/param-sync 完成: 触发{}个分组, 耗时={}ms", triggered.size(), elapsed);
        return Map.of("message", "手动触发完成", "triggered", triggered, "elapsedMs", elapsed);
    }

    /**
     * 手动触发医嘱同步（全量扫描）。
     */
    @PostMapping("/order-sync")
    public Map<String, Object> orderSync() {
        log.info("[API] POST /syn/order-sync - 手动触发医嘱同步");
        long start = System.currentTimeMillis();
        Map<String, Integer> result = orderSyncService.sync();
        long elapsed = System.currentTimeMillis() - start;
        log.info("[API] POST /syn/order-sync 完成: stats={}, 耗时={}ms", result, elapsed);
        return Map.of("message", "医嘱同步完成", "stats", result, "elapsedMs", elapsed);
    }

    /**
     * 手动触发源联动同步（全量扫描所有 enabled 规则）。
     */
    @PostMapping("/source-sync")
    public Map<String, Object> sourceSync() {
        log.info("[API] POST /syn/source-sync - 手动触发源联动同步");
        long start = System.currentTimeMillis();
        Map<String, Integer> result = sourceDrivenSyncService.syncAll();
        long elapsed = System.currentTimeMillis() - start;
        log.info("[API] POST /syn/source-sync 完成: stats={}, 耗时={}ms", result, elapsed);
        return Map.of("message", "源联动同步完成", "stats", result, "elapsedMs", elapsed);
    }

    /**
     * 手动触发血糖同步（全量扫描 lookback 窗口）。
     */
    @PostMapping("/bloodsugar-sync")
    public Map<String, Object> bloodSugarSync() {
        log.info("[API] POST /syn/bloodsugar-sync - 手动触发血糖同步");
        long start = System.currentTimeMillis();
        Map<String, Integer> result = bloodSugarSyncService.sync();
        long elapsed = System.currentTimeMillis() - start;
        log.info("[API] POST /syn/bloodsugar-sync 完成: stats={}, 耗时={}ms", result, elapsed);
        return Map.of("message", "血糖同步完成", "stats", result, "elapsedMs", elapsed);
    }

    /** 调试：查询某在院患者的 bedside 记录。 */
    @GetMapping("/patients/{patientId}/bedsides")
    public List<Bedside> bedsides(@PathVariable String patientId) {
        log.debug("[API] GET /syn/patients/{}/bedsides", patientId);
        List<Bedside> result = service.findBedsidesForPatient(patientId);
        log.info("[API] GET /syn/patients/{}/bedsides: 返回 {} 条", patientId, result.size());
        return result;
    }
}
