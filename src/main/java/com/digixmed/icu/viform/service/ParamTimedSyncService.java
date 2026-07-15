package com.digixmed.icu.viform.service;

import com.digixmed.icu.viform.config.SyncGroupsProperties;
import com.digixmed.icu.viform.config.SyncGroupsProperties.SyncGroup;
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
 * bedside 定时「值前推补写」同步服务。
 *
 * <p>核心流程：</p>
 * <ol>
 *   <li>查在科患者（status=admitted 且 icuAdmissionTime 不为空）</li>
 *   <li>按 patient.id 批量拉取该分组 code 对应的 bedside</li>
 *   <li>对每个 (患者, code)：取目标时间点之前最新一条有效（valid=true &amp;&amp; strVal非空）记录作为源</li>
 *   <li>若源存在且目标时间点 ≥ 入科时间 → upsert 一条 time=目标时间点的 bedside</li>
 *   <li>记录同步日志（幂等去重）</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParamTimedSyncService {

    private final PatientRepository patientRepository;
    private final BedsideRepository bedsideRepository;
    private final BedsideSyncLogRepository syncLogRepository;
    private final SyncGroupsProperties syncGroupsProperties;

    /** SmartCare 库模板（直接 upsert） */
    private final MongoTemplate smartCareMongoTemplate;

    /** 在院状态常量 */
    private static final String STATUS_ADMITTED = "admitted";

    /**
     * 对指定分组在指定目标时间执行一次前推补写同步。
     *
     * @param group      同步分组（codes + times）
     * @param targetTime 目标时间点（Asia/Shanghai 时区的 Date）
     */
    public void sync(SyncGroup group, Date targetTime) {
        String timeLabel = String.format("%tR", targetTime); // HH:mm
        log.info("[ParamSync] 开始同步 group={}, targetTime={}", group.getName(), timeLabel);

        // 1. 查在科患者（status=admitted 且已入科）
        List<Patient> patients = patientRepository.findByStatus(STATUS_ADMITTED);
        if (CollectionUtils.isEmpty(patients)) {
            log.info("[ParamSync] 无在院患者，跳过");
            return;
        }

        // 过滤：必须有 icuAdmissionTime
        List<Patient> admittedPatients = patients.stream()
                .filter(p -> p.getIcuAdmissionTime() != null)
                .collect(Collectors.toList());
        log.info("[ParamSync] 在科患者数量: {} (总在院: {})", admittedPatients.size(), patients.size());

        if (admittedPatients.isEmpty()) {
            return;
        }

        // 2. 批量拉取 bedside
        List<String> patientIds = admittedPatients.stream()
                .map(Patient::getId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());

        List<Bedside> allBedsides = bedsideRepository.findByPidInAndCodeIn(patientIds, group.getCodes());
        log.info("[ParamSync] 命中 bedside 记录: {} 条", allBedsides.size());

        // 按 pid → code → List<Bedside> 分组
        Map<String, Map<String, List<Bedside>>> bedsideTree = new HashMap<>();
        for (Bedside b : allBedsides) {
            if (!StringUtils.hasText(b.getPid()) || !StringUtils.hasText(b.getCode())) continue;
            bedsideTree.computeIfAbsent(b.getPid(), k -> new HashMap<>())
                    .computeIfAbsent(b.getCode(), k -> new ArrayList<>())
                    .add(b);
        }

        String editUser = syncGroupsProperties.getEditUser();
        Date now = new Date();

        // 3. 对每个患者 × 该组 code 执行前推补写
        int successCount = 0;
        int skipCount = 0;
        for (Patient patient : admittedPatients) {
            // 目标时间点早于入科时间 → 跳过整个患者该时间点
            if (targetTime.before(patient.getIcuAdmissionTime())) {
                log.debug("[ParamSync] pid={} 目标时间点 {} 早于 icuAdmissionTime，跳过所有code",
                        patient.getId(), timeLabel);
                skipCount += group.getCodes().size();
                continue;
            }

            Map<String, List<Bedside>> codeMap = bedsideTree.getOrDefault(patient.getId(), Collections.emptyMap());
            for (String code : group.getCodes()) {
                try {
                    boolean ok = syncOne(patient, code, targetTime, now,
                            codeMap.getOrDefault(code, Collections.emptyList()), editUser);
                    if (ok) successCount++; else skipCount++;
                } catch (Exception e) {
                    log.error("[ParamSync] 同步异常 pid={}, code={}, targetTime={}",
                            patient.getId(), code, timeLabel, e);
                    saveFailLog(patient, code, targetTime, e.getMessage());
                }
            }
        }
        log.info("[ParamSync] group={}, targetTime={} 完成: success={}, skip={}",
                group.getName(), timeLabel, successCount, skipCount);
    }

    // ==================== 单条同步 ====================

    /**
     * 对单个 (患者, code) 在当前目标时间点执行前推补写（变动跟随）。
     *
     * <p>不再使用 sync_log 做硬幂等——即使已有日志，只要源值(state: strVal/fVal/valid/remark)
     * 发生变化，仍会重新 upsert 更新目标记录。</p>
     *
     * @return true=生成/已存在记录, false=跳过
     */
    private boolean syncOne(Patient patient, String code, Date targetTime, Date now,
                            List<Bedside> bedsides, String editUser) {
        // --- 筛选有效源数据 ---
        // 条件：valid==true, strVal 非空, time <= now, time >= icuAdmissionTime
        int totalBeforeFilter = bedsides.size();
        List<Bedside> validSources = bedsides.stream()
                .filter(b -> Boolean.TRUE.equals(b.getValid()))
                .filter(b -> StringUtils.hasText(b.getStrVal()))
                .filter(b -> b.getTime() != null && !b.getTime().after(now))
                .filter(b -> !b.getTime().before(patient.getIcuAdmissionTime()))
                .collect(Collectors.toList());

        if (validSources.isEmpty()) {
            log.info("[ParamSync] 无可用源数据 pid={}, code={}, targetTime={}, 过滤前={}条",
                    patient.getId(), code, String.format("%tR", targetTime), totalBeforeFilter);
            saveSkipLog(patient, code, targetTime, null,
                    String.format("无可用源数据（valid=true & strVal非空 & time>=入科时间 过滤后为空，过滤前%d条）", totalBeforeFilter));
            return false;
        }

        // --- 取最新：按 time 降序，editTime 降序兜底 ---
        Bedside source = validSources.stream()
                .max(Comparator
                        .comparing(Bedside::getTime, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Bedside::getEditTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
        if (source == null) {
            saveSkipLog(patient, code, targetTime, null, "取最新源记录失败");
            return false;
        }

        log.info("[ParamSync] 选定源数据 pid={}, code={}, 源time={}, 源strVal={}, 候选{}条/原始{}条",
                patient.getId(), code, source.getTime(), source.getStrVal(),
                validSources.size(), totalBeforeFilter);

        // --- 变动跟随：读目标现值，比较 strVal/fVal/valid/remark ---
        Query query = new Query(Criteria.where("pid").is(patient.getId())
                .and("code").is(code)
                .and("time").is(targetTime));
        Bedside currentTarget = smartCareMongoTemplate.findOne(query, Bedside.class);

        if (currentTarget != null
                && Objects.equals(currentTarget.getStrVal(), source.getStrVal())
                && Objects.equals(currentTarget.getFVal(), source.getFVal())
                && Objects.equals(currentTarget.getValid(), source.getValid())
                && Objects.equals(currentTarget.getRemark(), source.getRemark())) {
            log.debug("[ParamSync] 现值与源值相同 pid={}, code={}, targetTime={}, SKIP",
                    patient.getId(), code, String.format("%tR", targetTime));

            // 仅首次没有日志时补一条 SKIP 日志
            Optional<BedsideSyncLog> existingLog = syncLogRepository
                    .findByPidAndCodeAndTargetTimePoint(patient.getId(), code, targetTime);
            if (!existingLog.isPresent()) {
                saveSkipLog(patient, code, targetTime, source.getId(), "现值与源值相同(变动跟随no-op)");
            }
            return true;
        }

        // --- Upsert 到 bedside 集合（变动跟随）---
        BedsideHistory syncHistory = new BedsideHistory();
        syncHistory.setTime(new Date());
        syncHistory.setAccountId(editUser);
        syncHistory.setDesc("系统定时同步-值前推补写");

        Update update = new Update()
                .set("strVal", source.getStrVal())          // 跟随源
                .set("fVal", source.getFVal())              // 跟随源
                .set("valid", source.getValid())            // 跟随源
                .set("remark", source.getRemark())          // 跟随源：remark 变化时目标跟着变
                .set("synRemark", "系统定时同步")            // 程序固定
                .set("editUser", editUser)                  // 程序固定
                .set("editTime", new Date())                // 同步元数据
                .set("history", Collections.singletonList(syncHistory))
                .setOnInsert("_class", Bedside.BEDSIDE_CLASS);

        smartCareMongoTemplate.upsert(query, update, Bedside.class);

        Bedside upserted = smartCareMongoTemplate.findOne(query, Bedside.class);
        String generatedId = upserted != null ? upserted.getId() : null;

        // --- 写同步日志（每次 upsert 都记，sourceKey=源bedside.id 允许重复）---
        BedsideSyncLog syncLog = buildLog(BedsideSyncLog.TYPE_PARAM, code, patient,
                source.getId(), targetTime, generatedId,
                BedsideSyncLog.RESULT_SUCCESS, "源记录 time=" + source.getTime());
        syncLogRepository.save(syncLog);

        log.info("[ParamSync] 补写成功 pid={}, code={}, targetTime={}, bedsideId={}, sourceTime={}",
                patient.getId(), code, String.format("%tR", targetTime), generatedId, source.getTime());
        return true;
    }

    // ==================== 日志工具方法 ====================

    private void saveSkipLog(Patient patient, String code, Date targetTime,
                             String sourceKey, String message) {
        BedsideSyncLog log = buildLog(BedsideSyncLog.TYPE_PARAM, code, patient,
                sourceKey, targetTime, null, BedsideSyncLog.RESULT_SKIP, message);
        syncLogRepository.save(log);
    }

    private void saveFailLog(Patient patient, String code, Date targetTime, String errorMsg) {
        BedsideSyncLog log = buildLog(BedsideSyncLog.TYPE_PARAM, code, patient,
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
        log.setSourceKey(sourceKey);
        log.setTargetTimePoint(targetTimePoint);
        log.setGeneratedBedsideId(generatedBedsideId);
        log.setResult(result);
        log.setMessage(message);
        log.setSyncTime(new Date());
        return log;
    }
}
