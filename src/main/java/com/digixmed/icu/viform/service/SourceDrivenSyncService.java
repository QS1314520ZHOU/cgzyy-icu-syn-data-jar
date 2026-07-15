package com.digixmed.icu.viform.service;

import com.digixmed.icu.viform.config.SourceSyncProperties;
import com.digixmed.icu.viform.config.SourceSyncProperties.Mapping;
import com.digixmed.icu.viform.config.SourceSyncProperties.Rule;
import com.digixmed.icu.viform.entity.Bedside;
import com.digixmed.icu.viform.entity.BedsideHistory;
import com.digixmed.icu.viform.entity.BedsideSyncLog;
import com.digixmed.icu.viform.entity.Patient;
import com.digixmed.icu.viform.repository.smartcare.BedsideRepository;
import com.digixmed.icu.viform.repository.smartcare.BedsideSyncLogRepository;
import com.digixmed.icu.viform.repository.smartcare.PatientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 源 code 驱动的同时间点跨 code 值联动同步服务。
 *
 * <p>核心流程：</p>
 * <ol>
 *   <li>查在院患者 → 收集 pids</li>
 *   <li>一次性批量拉取所有相关 code 的 bedside 记录（triggerCode + sourceCodes + targetCodes）</li>
 *   <li>按 pid → code → time 建索引</li>
 *   <li>对每位患者：找到 triggerCode 的有效记录（在 lookback 窗口内）</li>
 *   <li>对每条触发记录的时间点 T：
 *     <ul>
 *       <li>按 mapping.sourceCodes 优先级（有创优先）查找第一个在 T 有效的源记录</li>
 *       <li>读取目标 (pid, targetCode, T) 现值，与源值比较</li>
 *       <li>不同或不存在 → upsert；相同 → SKIP（变动跟随）</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>与 ParamTimedSyncService 的关键区别：</p>
 * <ul>
 *   <li>不通过 sync_log 做幂等——源值变动时目标必须更新（变动跟随）</li>
 *   <li>源 code 有优先级（有创优先）</li>
 *   <li>触发条件由 triggerCode 存在有效数据驱动（而非预定义时间点）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceDrivenSyncService {

    private final PatientRepository patientRepository;
    private final BedsideRepository bedsideRepository;
    private final BedsideSyncLogRepository syncLogRepository;
    private final SourceSyncProperties sourceSyncProperties;

    /** SmartCare 主库模板 */
    private final MongoTemplate smartCareMongoTemplate;

    private static final String STATUS_ADMITTED = "admitted";

    // ==================== 入口方法 ====================

    /**
     * 对所有 enabled rule 执行全量扫描同步。
     *
     * @return Map 含 total/success/skip/fail 计数
     */
    public Map<String, Integer> syncAll() {
        log.info("[SourceSync] ========== 开始全量源联动同步 ==========");
        int total = 0, success = 0, skip = 0, fail = 0;

        List<Rule> enabledRules = sourceSyncProperties.getRules().stream()
                .filter(r -> StringUtils.hasText(r.getTriggerCode()))
                .collect(Collectors.toList());

        if (enabledRules.isEmpty()) {
            log.info("[SourceSync] 无有效规则，跳过");
            return Map.of("total", 0, "success", 0, "skip", 0, "fail", 0);
        }

        for (Rule rule : enabledRules) {
            try {
                Map<String, Integer> stats = syncRule(rule);
                total += stats.getOrDefault("total", 0);
                success += stats.getOrDefault("success", 0);
                skip += stats.getOrDefault("skip", 0);
                fail += stats.getOrDefault("fail", 0);
            } catch (Exception e) {
                log.error("[SourceSync] 规则执行异常 rule={}", rule.getName(), e);
                fail++;
            }
        }

        log.info("[SourceSync] ========== 全量源联动同步完成: total={}, success={}, skip={}, fail={} ==========",
                total, success, skip, fail);
        return Map.of("total", total, "success", success, "skip", skip, "fail", fail);
    }

    /**
     * 对单条规则执行扫描同步。
     */
    public Map<String, Integer> syncRule(Rule rule) {
        log.info("[SourceSync] 开始执行规则: name={}, triggerCode={}", rule.getName(), rule.getTriggerCode());
        int total = 0, success = 0, skip = 0, fail = 0;

        // 1. 查在院患者
        List<Patient> patients = patientRepository.findByStatus(STATUS_ADMITTED);
        if (CollectionUtils.isEmpty(patients)) {
            log.info("[SourceSync] 无在院患者，跳过规则 {}", rule.getName());
            return Map.of("total", 0, "success", 0, "skip", 0, "fail", 0);
        }

        List<Patient> admittedPatients = patients.stream()
                .filter(p -> p.getIcuAdmissionTime() != null)
                .collect(Collectors.toList());
        log.info("[SourceSync] 规则[{}] 在科患者: {} 人", rule.getName(), admittedPatients.size());

        if (admittedPatients.isEmpty()) {
            return Map.of("total", 0, "success", 0, "skip", 0, "fail", 0);
        }

        // 2. 收集所有涉及的 code + pids
        List<String> pids = admittedPatients.stream()
                .map(Patient::getId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());

        Set<String> allCodes = new LinkedHashSet<>();
        allCodes.add(rule.getTriggerCode());
        for (Mapping m : rule.getMappings()) {
            allCodes.add(m.getTargetCode());
            allCodes.addAll(m.getSourceCodes());
        }
        List<String> codeList = new ArrayList<>(allCodes);
        log.info("[SourceSync] 规则[{}] 涉及 codes: {}", rule.getName(), codeList);

        // 3. 批量拉取 bedside
        List<Bedside> allBedsides = bedsideRepository.findByPidInAndCodeIn(pids, codeList);
        log.info("[SourceSync] 规则[{}] 批量拉取 bedside: {} 条", rule.getName(), allBedsides.size());

        // 4. 建索引: pid → code → timeMs → Bedside（同 time 取 editTime 最新）
        Map<String, Map<String, Map<Long, Bedside>>> index = buildIndex(allBedsides);

        // 5. lookback 窗口
        Date now = new Date();
        long lookbackMs = sourceSyncProperties.getLookbackMinutes() * 60_000L;
        Date windowStart = new Date(now.getTime() - lookbackMs);
        long toleranceMs = sourceSyncProperties.getTimeToleranceSeconds() * 1000L;

        String editUser = sourceSyncProperties.getEditUser();

        // 6. 遍历患者
        Map<String, Patient> patientMap = admittedPatients.stream()
                .collect(Collectors.toMap(Patient::getId, p -> p, (a, b) -> a));

        for (String pid : pids) {
            Patient patient = patientMap.get(pid);
            if (patient == null) continue;

            Map<String, Map<Long, Bedside>> patientData = index.getOrDefault(pid, Collections.emptyMap());

            // 6a. 取 triggerCode 在 lookback 窗口内的有效记录
            Map<Long, Bedside> triggerRecords = patientData.getOrDefault(rule.getTriggerCode(), Collections.emptyMap());
            List<Bedside> validTriggers = triggerRecords.values().stream()
                    .filter(b -> Boolean.TRUE.equals(b.getValid()))
                    .filter(b -> hasValue(b))
                    .filter(b -> b.getTime() != null
                            && !b.getTime().before(windowStart)
                            && !b.getTime().after(now))
                    .collect(Collectors.toList());

            if (validTriggers.isEmpty()) {
                log.debug("[SourceSync] pid={} 无 {} 的有效触发记录", pid, rule.getTriggerCode());
                continue;
            }

            // 6b. 对每条触发记录的时间点 T 执行映射
            for (Bedside trigger : validTriggers) {
                Date timeT = trigger.getTime();
                long timeTMs = timeT.getTime();
                total += rule.getMappings().size();

                for (Mapping mapping : rule.getMappings()) {
                    try {
                        // 按 sourceCodes 优先级找第一个在 T(±tolerance) 有效的源记录（有创优先）
                        Bedside source = findFirstValidSource(patientData, mapping.getSourceCodes(),
                                timeTMs, toleranceMs);
                        if (source == null) {
                            log.debug("[SourceSync] pid={}, T={}, target={}: 无有效源数据，跳过",
                                    pid, timeT, mapping.getTargetCode());
                            saveSkipLog(patient, mapping.getTargetCode(), timeT,
                                    "无有效源数据（sourceCodes=" + mapping.getSourceCodes() + "）");
                            skip++;
                            continue;
                        }

                        // 读目标现值
                        Map<Long, Bedside> targetMap = patientData.getOrDefault(mapping.getTargetCode(),
                                Collections.emptyMap());
                        Bedside currentTarget = targetMap.get(timeTMs);

                        // 变动跟随：比较现值与源值
                        if (currentTarget != null
                                && Objects.equals(currentTarget.getStrVal(), source.getStrVal())
                                && Objects.equals(currentTarget.getFVal(), source.getFVal())) {
                            log.debug("[SourceSync] pid={}, T={}, target={}: 现值与源值相同，SKIP",
                                    pid, timeT, mapping.getTargetCode());
                            // 不记 skip 日志以避免日志膨胀（真正的 no-op）
                            // 如需追踪可取消下行注释：
                            // saveSkipLog(patient, mapping.getTargetCode(), timeT, "现值与源值相同(no-op)");
                            skip++;
                            continue;
                        }

                        // Upsert 目标记录
                        Query query = new Query(Criteria.where("pid").is(pid)
                                .and("code").is(mapping.getTargetCode())
                                .and("time").is(timeT));

                        BedsideHistory syncHistory = new BedsideHistory();
                        syncHistory.setTime(new Date());
                        syncHistory.setAccountId(editUser);
                        syncHistory.setDesc("系统源联动同步-source=" + source.getCode()
                                + "-trigger=" + rule.getTriggerCode());

                        Update update = new Update()
                                .set("strVal", source.getStrVal())
                                .set("fVal", source.getFVal())
                                .set("valid", true)
                                .set("editUser", editUser)
                                .set("editTime", new Date())
                                .set("remark", "源联动同步")
                                .set("history", Collections.singletonList(syncHistory));

                        smartCareMongoTemplate.upsert(query, update, Bedside.class);

                        Bedside upserted = smartCareMongoTemplate.findOne(query, Bedside.class);
                        String generatedId = upserted != null ? upserted.getId() : null;

                        // 写同步日志：sourceKey=null 避免 sparse unique 索引冲突（允许变动重写）
                        BedsideSyncLog syncLog = buildLog(BedsideSyncLog.TYPE_SOURCE,
                                mapping.getTargetCode(), patient, null, timeT, generatedId,
                                BedsideSyncLog.RESULT_SUCCESS,
                                String.format("sourceCode=%s, sourceTime=%s, triggerCode=%s",
                                        source.getCode(), source.getTime(), rule.getTriggerCode()));
                        syncLogRepository.save(syncLog);

                        log.info("[SourceSync] ✓ pid={}, T={}, {} ← {} (源={})",
                                pid, String.format("%tR", timeT),
                                mapping.getTargetCode(), source.getStrVal(), source.getCode());
                        success++;

                        // 更新内存索引（后续同 pid 同 T 的其他 mapping 能读到最新值）
                        if (upserted != null) {
                            patientData.computeIfAbsent(mapping.getTargetCode(), k -> new HashMap<>())
                                    .put(timeTMs, upserted);
                        }
                    } catch (Exception e) {
                        log.error("[SourceSync] 异常 pid={}, T={}, target={}, sourceCodes={}",
                                pid, timeT, mapping.getTargetCode(), mapping.getSourceCodes(), e);
                        saveFailLog(patient, mapping.getTargetCode(), timeT, e.getMessage());
                        fail++;
                    }
                }
            }
        }

        log.info("[SourceSync] 规则[{}] 完成: total={}, success={}, skip={}, fail={}",
                rule.getName(), total, success, skip, fail);
        return Map.of("total", total, "success", success, "skip", skip, "fail", fail);
    }

    // ==================== 内部工具方法 ====================

    /**
     * 构建 bedside 三级索引：pid → code → timeEpochMs → Bedside。
     *
     * <p>同一 (pid, code, timeMs) 保留 editTime 最新的一条。</p>
     */
    private Map<String, Map<String, Map<Long, Bedside>>> buildIndex(List<Bedside> bedsides) {
        Map<String, Map<String, Map<Long, Bedside>>> index = new HashMap<>();
        for (Bedside b : bedsides) {
            if (!StringUtils.hasText(b.getPid()) || !StringUtils.hasText(b.getCode())
                    || b.getTime() == null) {
                continue;
            }
            long timeMs = b.getTime().getTime();
            Map<String, Map<Long, Bedside>> pidMap = index.computeIfAbsent(b.getPid(),
                    k -> new HashMap<>());
            Map<Long, Bedside> codeMap = pidMap.computeIfAbsent(b.getCode(),
                    k -> new HashMap<>());

            Bedside existing = codeMap.get(timeMs);
            if (existing == null || (b.getEditTime() != null
                    && (existing.getEditTime() == null
                    || b.getEditTime().after(existing.getEditTime())))) {
                codeMap.put(timeMs, b);
            }
        }
        return index;
    }

    /**
     * 按 sourceCodes 顺序查找第一个在 T(±toleranceMs) 有效的源记录（有创优先）。
     *
     * @return 有效源记录，无则 null
     */
    private Bedside findFirstValidSource(Map<String, Map<Long, Bedside>> patientData,
                                         List<String> sourceCodes,
                                         long timeTMs, long toleranceMs) {
        for (String sourceCode : sourceCodes) {
            Map<Long, Bedside> records = patientData.getOrDefault(sourceCode, Collections.emptyMap());
            // 在 [timeTMs - toleranceMs, timeTMs + toleranceMs] 范围内查找
            for (Map.Entry<Long, Bedside> entry : records.entrySet()) {
                if (Math.abs(entry.getKey() - timeTMs) <= toleranceMs) {
                    Bedside b = entry.getValue();
                    if (Boolean.TRUE.equals(b.getValid()) && hasValue(b)) {
                        return b;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 判断 bedside 是否"有值"（strVal 非空 或 fVal 非空）。
     */
    private boolean hasValue(Bedside b) {
        return StringUtils.hasText(b.getStrVal()) || StringUtils.hasText(b.getFVal());
    }

    // ==================== 日志工具方法 ====================

    private void saveSkipLog(Patient patient, String code, Date targetTime,
                             String message) {
        BedsideSyncLog log = buildLog(BedsideSyncLog.TYPE_SOURCE, code, patient,
                null, targetTime, null, BedsideSyncLog.RESULT_SKIP, message);
        syncLogRepository.save(log);
    }

    private void saveFailLog(Patient patient, String code, Date targetTime, String errorMsg) {
        BedsideSyncLog log = buildLog(BedsideSyncLog.TYPE_SOURCE, code, patient,
                null, targetTime, null, BedsideSyncLog.RESULT_FAIL,
                errorMsg != null ? errorMsg.substring(0, Math.min(errorMsg.length(), 500)) : "未知异常");
        syncLogRepository.save(log);
    }

    private BedsideSyncLog buildLog(String syncType, String code, Patient patient,
                                    String sourceKey, Date targetTimePoint,
                                    String generatedBedsideId, String result, String message) {
        BedsideSyncLog log = new BedsideSyncLog();
        log.setSyncType(syncType);
        log.setCode(code);
        log.setPid(patient.getId());
        log.setMrn(patient.getMrn());
        log.setSourceKey(sourceKey); // null for SOURCE联动 → 允许变动重写
        log.setTargetTimePoint(targetTimePoint);
        log.setGeneratedBedsideId(generatedBedsideId);
        log.setResult(result);
        log.setMessage(message);
        log.setSyncTime(new Date());
        return log;
    }
}
