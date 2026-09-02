package com.digixmed.icu.viform.controller;

import com.digixmed.icu.viform.config.OrderSyncProperties;
import com.digixmed.icu.viform.config.SyncGroupsProperties;
import com.digixmed.icu.viform.entity.Bedside;
import com.digixmed.icu.viform.service.AdmittedPatientBedsideService;
import com.digixmed.icu.viform.service.OrderSyncService;
import com.digixmed.icu.viform.service.ParamTimedSyncService;
import com.digixmed.icu.viform.service.FirstAdmissionAssessmentSyncService;
import com.digixmed.icu.viform.service.SourceDrivenSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "ICU 数据同步接口", description = "手动触发、调试、健康检查")
public class SynDataController {

    private final AdmittedPatientBedsideService service;
    private final ParamTimedSyncService paramTimedSyncService;
    private final OrderSyncService orderSyncService;
    private final SourceDrivenSyncService sourceDrivenSyncService;
    private final FirstAdmissionAssessmentSyncService firstAdmissionAssessmentSyncService;
    private final SyncGroupsProperties syncGroupsProperties;
    private final OrderSyncProperties orderSyncProperties;

    @Operation(summary = "健康检查", description = "返回服务状态、当前时间、同步分组概况、医嘱同步配置等信息")
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

    @Operation(summary = "bedside 分发处理",
            description = "查询所有在院患者的 bedside 记录，按 code 分发给对应处理器执行业务逻辑")
    @PostMapping("/process")
    public Map<String, Object> process() {
        log.info("[API] POST /syn/process - 手动触发策略分发同步");
        long start = System.currentTimeMillis();
        int count = service.process();
        long elapsed = System.currentTimeMillis() - start;
        log.info("[API] POST /syn/process 完成: handled={}, 耗时={}ms", count, elapsed);
        return Map.of("handled", count, "type", "strategy-dispatch", "elapsedMs", elapsed);
    }

    @Operation(summary = "值前推补写同步",
            description = "在固定时间点（如02:00、06:00、10:00...），把bedside中最新的一条有效数据复制到目标时间点。"
                    + "用于护士未及时录入时，系统自动用最近一次的值补上。"
                    + "不传参数时，对当前时间窗口内的分组执行一次补偿同步。")
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

    @Operation(summary = "医嘱同步",
            description = "扫描在院患者的医嘱数据（如鼻饲液、饮食类型），同步到bedside对应code中")
    @PostMapping("/order-sync")
    public Map<String, Object> orderSync() {
        log.info("[API] POST /syn/order-sync - 手动触发医嘱同步");
        long start = System.currentTimeMillis();
        Map<String, Integer> result = orderSyncService.sync();
        long elapsed = System.currentTimeMillis() - start;
        log.info("[API] POST /syn/order-sync 完成: stats={}, 耗时={}ms", result, elapsed);
        return Map.of("message", "医嘱同步完成", "stats", result, "elapsedMs", elapsed);
    }

    @Operation(summary = "源联动同步",
            description = "当某个触发code（如IABP反博压、心输出量指数）出现新数据时，"
                    + "把同一时间点的其他code值联动写入目标code。"
                    + "例如：IABP反博压记录出现时，把同时刻的心率、有创血压联动写到iabp心率、iabp收缩压等字段")
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
     * 血糖→bedside 同步已停用。
     *
     * @deprecated 该接口不再执行任何写入操作，始终返回停用提示。
     */
    @Deprecated
    @PostMapping("/bloodsugar-sync")
    public Map<String, Object> bloodSugarSync() {
        log.info("[API] POST /syn/bloodsugar-sync - 血糖同步已停用");
        return Map.of("message", "血糖到bedside同步已停用", "stats",
                Map.of("total", 0, "success", 0, "skip", 0, "fail", 0));
    }

    @Operation(summary = "入科评估全量同步",
            description = "从bedside和score中取所有当天入科患者的第一次有效评估数据，"
                    + "增量同步到入院/入科护理评估单（dFormData）。"
                    + "包括：疼痛评分、Braden压疮、ADL生活自理、非计划拔管、生命体征、意识状态、跌倒评估等")
    @PostMapping("/first-admission-assessment")
    public Map<String, Object> firstAdmissionAssessment() {
        log.info("[API] POST /syn/first-admission-assessment - 手动触发首次入科评估同步");
        long start = System.currentTimeMillis();
        FirstAdmissionAssessmentSyncService.SyncResult result =
                firstAdmissionAssessmentSyncService.syncAllAdmittedPatients();
        long elapsed = System.currentTimeMillis() - start;
        log.info("[API] POST /syn/first-admission-assessment 完成: 耗时={}ms", elapsed);
        return Map.of("success", true, "data", result.toMap(), "elapsedMs", elapsed);
    }

    @Operation(summary = "按患者ID同步入科评估",
            description = "指定patientId，手动触发该患者的入院/入科评估单同步。"
                    + "跳过动态频次控制，直接查询该患者的bedside和score数据并同步到评估单")
    @PostMapping("/first-admission-assessment/{patientId}")
    public Map<String, Object> firstAdmissionAssessmentByPatientId(
            @Parameter(description = "患者ID（patient._id）") @PathVariable String patientId) {
        log.info("[API] POST /syn/first-admission-assessment/{} - 手动同步指定患者", patientId);
        long start = System.currentTimeMillis();
        FirstAdmissionAssessmentSyncService.SyncResult result =
                firstAdmissionAssessmentSyncService.syncByPatientId(patientId);
        long elapsed = System.currentTimeMillis() - start;
        log.info("[API] POST /syn/first-admission-assessment/{} 完成: 耗时={}ms", patientId, elapsed);
        return Map.of("success", true, "patientId", patientId, "data", result.toMap(), "elapsedMs", elapsed);
    }

    @Operation(summary = "查询患者bedside记录",
            description = "查询指定在院患者的所有bedside记录，用于调试和排查数据同步问题")
    @GetMapping("/patients/{patientId}/bedsides")
    public List<Bedside> bedsides(
            @Parameter(description = "患者ID（patient._id）") @PathVariable String patientId) {
        log.debug("[API] GET /syn/patients/{}/bedsides", patientId);
        List<Bedside> result = service.findBedsidesForPatient(patientId);
        log.info("[API] GET /syn/patients/{}/bedsides: 返回 {} 条", patientId, result.size());
        return result;
    }
}
