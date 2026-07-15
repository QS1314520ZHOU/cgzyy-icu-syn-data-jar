package com.digixmed.icu.viform.service;

import com.digixmed.icu.viform.config.BloodSugarSyncProperties;
import com.digixmed.icu.viform.entity.*;
import com.digixmed.icu.viform.repository.smartcare.BedsideSyncLogRepository;
import com.digixmed.icu.viform.repository.smartcare.BloodSugarRepository;
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

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 血糖同步服务（bloodSugar 集合驱动 + 小时分桶 + 整点生成 bedside）。
 *
 * <p>核心流程：</p>
 * <ol>
 *   <li>查在院患者 → pids</li>
 *   <li>查 bloodSugar：pid in pids && valid=true && time in lookback 窗口</li>
 *   <li>按 pid 分组，再按 Asia/Shanghai 小时桶分组，每桶取最新一条</li>
 *   <li>目标时间 = 该小时桶的整点（转回 Date/UTC）</li>
 *   <li>变动跟随：读目标 bedside(pid, param_XueTang, targetTime) 现值 → 相同 skip，不同 upsert</li>
 *   <li>写 BedsideSyncLog(type=血糖同步, sourceKey=null)</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BloodSugarSyncService {

    private final PatientRepository patientRepository;
    private final BloodSugarRepository bloodSugarRepository;
    private final BedsideSyncLogRepository syncLogRepository;
    private final BloodSugarSyncProperties bloodSugarSyncProperties;

    /** SmartCare 主库模板 */
    private final MongoTemplate smartCareMongoTemplate;

    private static final String STATUS_ADMITTED = "admitted";

    /** Asia/Shanghai 时区 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 执行一次血糖同步（全量扫描 lookback 窗口内数据）。
     *
     * @return Map 含 total/success/skip/fail 计数
     */
    public Map<String, Integer> sync() {
        log.info("[BloodSugarSync] ========== 开始血糖同步 ==========");

        int total = 0, success = 0, skip = 0, fail = 0;

        // 检查是否启用
        if (!bloodSugarSyncProperties.isEnabled()) {
            log.info("[BloodSugarSync] 已禁用 (enabled=false)，跳过");
            return Map.of("total", 0, "success", 0, "skip", 0, "fail", 0);
        }

        // 1. 查在院患者
        List<Patient> patients = patientRepository.findByStatus(STATUS_ADMITTED);
        if (CollectionUtils.isEmpty(patients)) {
            log.info("[BloodSugarSync] 无在院患者，跳过");
            return Map.of("total", 0, "success", 0, "skip", 0, "fail", 0);
        }

        List<String> pids = patients.stream()
                .map(Patient::getId)
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.toList());
        log.info("[BloodSugarSync] 在院患者: {} 人", pids.size());

        if (pids.isEmpty()) {
            return Map.of("total", 0, "success", 0, "skip", 0, "fail", 0);
        }

        // 构建 pid → Patient 映射
        Map<String, Patient> patientMap = new HashMap<>();
        for (Patient p : patients) {
            if (StringUtils.hasText(p.getId())) {
                patientMap.put(p.getId(), p);
            }
        }

        // 2. 查 bloodSugar（lookback 窗口 + valid=true）
        Date now = new Date();
        int lookbackHours = bloodSugarSyncProperties.getLookbackHours();
        Date windowStart = new Date(now.getTime() - lookbackHours * 3600_000L);

        List<BloodSugar> bloodSugars = bloodSugarRepository
                .findByPidInAndValidTrueAndTimeGreaterThanEqual(pids, windowStart);
        log.info("[BloodSugarSync] 查询到 bloodSugar: {} 条 (近{}小时, valid=true)",
                bloodSugars.size(), lookbackHours);

        if (CollectionUtils.isEmpty(bloodSugars)) {
            log.info("[BloodSugarSync] 无有效血糖数据，跳过");
            return Map.of("total", 0, "success", 0, "skip", 0, "fail", 0);
        }

        // 3. 按 pid → 小时桶分组，每桶取最新
        String targetCode = bloodSugarSyncProperties.getTargetCode();
        String editUser = bloodSugarSyncProperties.getEditUser();

        // 按 pid 分组
        Map<String, List<BloodSugar>> byPid = new HashMap<>();
        for (BloodSugar bs : bloodSugars) {
            if (!StringUtils.hasText(bs.getPid())) continue;
            byPid.computeIfAbsent(bs.getPid(), k -> new ArrayList<>()).add(bs);
        }

        for (Map.Entry<String, List<BloodSugar>> entry : byPid.entrySet()) {
            String pid = entry.getKey();
            Patient patient = patientMap.get(pid);
            if (patient == null) continue;

            // 3a. 按 Asia/Shanghai 小时桶分组，每桶取最新一条
            Map<LocalDateTime, BloodSugar> bucketBest = selectBestPerHour(entry.getValue());

            // 3b. 每条最佳记录生成 bedside
            for (Map.Entry<LocalDateTime, BloodSugar> bucket : bucketBest.entrySet()) {
                LocalDateTime bucketStartLocal = bucket.getKey();
                BloodSugar source = bucket.getValue();
                total++;

                try {
                    // 计算 targetTime：上海整点 → UTC Date
                    ZonedDateTime targetShanghai = bucketStartLocal.atZone(ZONE);
                    Date targetTime = Date.from(targetShanghai.toInstant());

                    // 读目标 bedside 现值（变动跟随）
                    Query targetQuery = new Query(Criteria.where("pid").is(pid)
                            .and("code").is(targetCode)
                            .and("time").is(targetTime));
                    Bedside currentTarget = smartCareMongoTemplate.findOne(targetQuery, Bedside.class);

                    String sourceStrVal = source.getResult();
                    String sourceFVal = source.getResult();

                    // 比较现值与期望源值
                    if (currentTarget != null
                            && Objects.equals(currentTarget.getStrVal(), sourceStrVal)
                            && Objects.equals(currentTarget.getFVal(), sourceFVal)) {
                        log.debug("[BloodSugarSync] pid={}, targetTime={}: 现值相同 SKIP",
                                pid, targetTime);
                        skip++;
                        continue;
                    }

                    // Upsert bedside 记录
                    BedsideHistory syncHistory = new BedsideHistory();
                    syncHistory.setTime(new Date());
                    syncHistory.setAccountId(editUser);
                    syncHistory.setDesc("系统血糖同步-sourceId=" + source.getId());

                    String synRemark = String.format("血糖同步 | detectionProject=%s | specimenSource=%s",
                            source.getDetectionProject() != null ? source.getDetectionProject() : "",
                            source.getSpecimenSource() != null ? source.getSpecimenSource() : "");

                    Update update = new Update()
                            .set("strVal", sourceStrVal)
                            .set("fVal", sourceFVal)
                            .set("valid", true)
                            .set("synRemark", synRemark)                    // 血糖同步说明(程序固定)
                            .set("editUser", editUser)                      // 程序固定
                            .set("editTime", new Date())                    // 同步元数据
                            .set("history", Collections.singletonList(syncHistory))
                            .setOnInsert("_class", Bedside.BEDSIDE_CLASS);
                    // 注意：不写 remark，避免覆盖目标 bedside 已有业务 remark

                    smartCareMongoTemplate.upsert(targetQuery, update, Bedside.class);

                    Bedside upserted = smartCareMongoTemplate.findOne(targetQuery, Bedside.class);
                    String generatedId = upserted != null ? upserted.getId() : null;

                    // 写同步日志（sourceKey=null 避免 sparse unique 冲突，允许变动重写）
                    BedsideSyncLog syncLog = buildLog(BedsideSyncLog.TYPE_BLOODSUGAR,
                            targetCode, patient, null, targetTime, generatedId,
                            BedsideSyncLog.RESULT_SUCCESS,
                            String.format("bloodSugarId=%s, sourceTime=%s, result=%s, bucket=%s",
                                    source.getId(), source.getTime(), source.getResult(),
                                    bucketStartLocal));
                    syncLogRepository.save(syncLog);

                    log.info("[BloodSugarSync] ✓ pid={}, bucket={}, targetTime={}, result={}, bedsideId={}",
                            pid, bucketStartLocal, targetTime, source.getResult(), generatedId);
                    success++;

                } catch (Exception e) {
                    log.error("[BloodSugarSync] 异常 pid={}, bloodSugarId={}, bucket={}",
                            pid, source.getId(), bucketStartLocal, e);
                    saveFailLog(patient, targetCode, e.getMessage(), source);
                    fail++;
                }
            }
        }

        log.info("[BloodSugarSync] ========== 完成: total={}, success={}, skip={}, fail={} ==========",
                total, success, skip, fail);
        return Map.of("total", total, "success", success, "skip", skip, "fail", fail);
    }

    // ==================== 小时分桶取最新 ====================

    /**
     * 将同一患者的 bloodSugar 记录按 Asia/Shanghai 小时分桶，每桶取最新一条。
     *
     * <p>排序规则：time 降序 → _id 降序（ObjectId 单调递增保证写入顺序）。</p>
     *
     * @param records 该患者的 bloodSugar 列表
     * @return 桶起始时间 (Asia/Shanghai LocalDateTime) → 该桶最新记录
     */
    private Map<LocalDateTime, BloodSugar> selectBestPerHour(List<BloodSugar> records) {
        Map<LocalDateTime, BloodSugar> best = new LinkedHashMap<>();

        for (BloodSugar bs : records) {
            if (bs.getTime() == null) continue;

            // bloodSugar.time 是 UTC → 转 Asia/Shanghai → 截断到整点
            ZonedDateTime shanghaiTime = bs.getTime().toInstant().atZone(ZONE);
            LocalDateTime bucketKey = shanghaiTime.truncatedTo(ChronoUnit.HOURS).toLocalDateTime();

            BloodSugar existing = best.get(bucketKey);
            if (existing == null) {
                best.put(bucketKey, bs);
            } else if (isNewer(bs, existing)) {
                best.put(bucketKey, bs);
            }
        }
        return best;
    }

    /**
     * 判断 a 是否比 b 更新：先比 time 降序，time 相同则 _id 降序。
     */
    private boolean isNewer(BloodSugar a, BloodSugar b) {
        if (a.getTime() == null || b.getTime() == null) {
            return a.getTime() != null;
        }
        int cmp = a.getTime().compareTo(b.getTime());
        if (cmp != 0) return cmp > 0;

        // time 相同：比 _id（ObjectId 字符串单调递增）
        String idA = a.getId();
        String idB = b.getId();
        if (idA != null && idB != null) {
            return idA.compareTo(idB) > 0;
        }
        return idA != null; // 有 id 的优先
    }

    // ==================== 日志工具方法 ====================

    private void saveFailLog(Patient patient, String code, String errorMsg, BloodSugar source) {
        BedsideSyncLog log = new BedsideSyncLog();
        log.setSyncType(BedsideSyncLog.TYPE_BLOODSUGAR);
        log.setCode(code);
        log.setPid(patient.getId());
        log.setMrn(patient.getMrn());
        log.setSourceKey(null);
        log.setTargetTimePoint(source.getTime());
        log.setResult(BedsideSyncLog.RESULT_FAIL);
        log.setMessage("bloodSugarId=" + source.getId() + " | "
                + (errorMsg != null ? errorMsg.substring(0, Math.min(errorMsg.length(), 400)) : "未知异常"));
        log.setSyncTime(new Date());
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
