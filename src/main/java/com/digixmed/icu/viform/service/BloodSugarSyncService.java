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
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    // ==================== 全量同步 ====================

    /**
     * 执行一次血糖同步（全量扫描 lookback 窗口内数据）。
     *
     * @return Map 含 total/success/skip/fail 计数
     */
    public Map<String, Integer> sync() {
        log.info("[BloodSugarSync] ========== 开始血糖同步 ==========");

        int total = 0, success = 0, skip = 0, fail = 0;

        if (!bloodSugarSyncProperties.isEnabled()) {
            log.info("[BloodSugarSync] 已禁用 (enabled=false)，跳过");
            return Map.of("total", 0, "success", 0, "skip", 0, "fail", 0);
        }

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

        Map<String, Patient> patientMap = new HashMap<>();
        for (Patient p : patients) {
            if (StringUtils.hasText(p.getId())) patientMap.put(p.getId(), p);
        }

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

        Map<String, List<BloodSugar>> byPid = new HashMap<>();
        for (BloodSugar bs : bloodSugars) {
            if (!StringUtils.hasText(bs.getPid())) continue;
            byPid.computeIfAbsent(bs.getPid(), k -> new ArrayList<>()).add(bs);
        }

        for (Map.Entry<String, List<BloodSugar>> entry : byPid.entrySet()) {
            String pid = entry.getKey();
            Patient patient = patientMap.get(pid);
            if (patient == null) continue;

            Map<LocalDateTime, BloodSugar> bucketBest = selectBestPerHour(entry.getValue());

            for (Map.Entry<LocalDateTime, BloodSugar> bucket : bucketBest.entrySet()) {
                total++;
                try {
                    boolean ok = upsertBedsideForBucket(patient, pid, bucket.getKey(), bucket.getValue());
                    if (ok) success++; else skip++;
                } catch (Exception e) {
                    log.error("[BloodSugarSync] 异常 pid={}, bloodSugarId={}, bucket={}",
                            pid, bucket.getValue().getId(), bucket.getKey(), e);
                    Patient p = patientMap.get(pid);
                    if (p != null) saveFailLog(p, bloodSugarSyncProperties.getTargetCode(),
                            e.getMessage(), bucket.getValue());
                    fail++;
                }
            }
        }

        log.info("[BloodSugarSync] ========== 完成: total={}, success={}, skip={}, fail={} ==========",
                total, success, skip, fail);
        return Map.of("total", total, "success", success, "skip", skip, "fail", fail);
    }

    // ==================== 定向桶重算（供接收接口调用） ====================

    /**
     * 对受影响的整点小时桶做定向重算，保证 bloodSugar 明细与 bedside 一致。
     *
     * <p>接收接口写库成功后调用此方法，替代全量 sync()。
     * 桶内仍有 valid=true 明细 → upsert 最新一条；
     * 桶内已无 valid=true 明细 → 逻辑删除对应 bedside（仅限血糖同步来源）。</p>
     *
     * @param pid       患者 ID
     * @param sourceTime bloodSugar.time（UTC），用于定位整点小时桶
     */
    public void resyncBucket(String pid, Date sourceTime) {
        if (sourceTime == null || !StringUtils.hasText(pid)) {
            log.warn("[BloodSugarSync] resyncBucket 参数无效 pid={}, sourceTime={}", pid, sourceTime);
            return;
        }

        // 1. 计算桶边界（与 sync() 完全一致的算法）
        LocalDateTime bucketStartLocal = sourceTime.toInstant().atZone(ZONE)
                .truncatedTo(ChronoUnit.HOURS).toLocalDateTime();
        Date targetTime = Date.from(bucketStartLocal.atZone(ZONE).toInstant());
        Date bucketStart = targetTime;  // 桶起点(UTC)
        Date bucketEnd = Date.from(bucketStartLocal.plusHours(1).atZone(ZONE).toInstant()); // 桶终点(不含)

        log.info("[BloodSugarSync] resyncBucket pid={}, bucket=[{}, {}), targetTime={}",
                pid, bucketStart, bucketEnd, targetTime);

        // 2. 查该患者该桶内 valid=true 明细（同 key 链式 gte/lt，不冲突）
        Query bucketQuery = new Query(Criteria.where("pid").is(pid)
                .and("valid").is(true)
                .and("time").gte(bucketStart).lt(bucketEnd));
        List<BloodSugar> validInBucket = smartCareMongoTemplate.find(bucketQuery, BloodSugar.class);

        String targetCode = bloodSugarSyncProperties.getTargetCode();
        String editUser = bloodSugarSyncProperties.getEditUser();

        // 3. 查找患者（用于日志）
        Patient patient = patientRepository.findById(pid).orElse(null);

        if (!CollectionUtils.isEmpty(validInBucket)) {
            // 3a. 桶内有数据 → 取最新一条 upsert
            BloodSugar best = validInBucket.stream()
                    .max(this::compareBest).orElse(null);
            if (best != null) {
                try {
                    upsertBedsideForBucket(patient, pid, bucketStartLocal, best);
                    log.info("[BloodSugarSync] resyncBucket 拿其它值: pid={}, bucket={}, result={}",
                            pid, bucketStartLocal, best.getResult());
                } catch (Exception e) {
                    log.error("[BloodSugarSync] resyncBucket upsert异常 pid={}, bucket={}", pid, bucketStartLocal, e);
                    if (patient != null) saveFailLog(patient, targetCode, e.getMessage(), best);
                }
            }
        } else {
            // 3b. 桶内无数据 → 检查是否需要逻辑删除 bedside
            Query targetQuery = new Query(Criteria.where("pid").is(pid)
                    .and("code").is(targetCode)
                    .and("time").is(targetTime));
            Bedside currentBedside = smartCareMongoTemplate.findOne(targetQuery, Bedside.class);

            if (currentBedside == null) {
                log.info("[BloodSugarSync] resyncBucket 空桶无bedside: pid={}, targetTime={}", pid, targetTime);
                return;
            }

            // 安全校验：只删除血糖同步来源的 bedside
            boolean isBloodSugarSource = (currentBedside.getSynRemark() != null
                    && currentBedside.getSynRemark().startsWith("血糖同步"))
                    || (editUser.equals(currentBedside.getEditUser()));
            if (!isBloodSugarSource) {
                log.info("[BloodSugarSync] resyncBucket 空桶但bedside非血糖来源，不动: pid={}, targetTime={}, synRemark={}, editUser={}",
                        pid, targetTime, currentBedside.getSynRemark(), currentBedside.getEditUser());
                return;
            }

            // 逻辑删除
            BedsideHistory delHistory = new BedsideHistory();
            delHistory.setTime(new Date());
            delHistory.setAccountId(editUser);
            delHistory.setDesc("血糖同步删除-sourceTime=" + sourceTime);

            Update delUpdate = new Update()
                    .set("valid", false)
                    .set("editUser", editUser)
                    .set("editTime", new Date())
                    .set("synRemark", "血糖同步-源已删除")
                    .push("history", delHistory);

            smartCareMongoTemplate.updateFirst(targetQuery, delUpdate, Bedside.class);

            // 写同步日志
            BedsideSyncLog syncLog = buildLog(BedsideSyncLog.TYPE_BLOODSUGAR, targetCode, patient,
                    null, targetTime, currentBedside.getId(),
                    BedsideSyncLog.RESULT_SUCCESS,
                    "源删除→bedside失效, targetTime=" + targetTime);
            syncLogRepository.save(syncLog);

            log.info("[BloodSugarSync] resyncBucket 跟随删除: pid={}, targetTime={}, bedsideId={}",
                    pid, targetTime, currentBedside.getId());
        }
    }

    // ==================== 单桶 upsert（抽取复用） ====================

    /**
     * 对单个小时桶执行 bedside upsert（变动跟随）。
     *
     * <p>由 sync() 和 resyncBucket() 共用，保证行为完全一致。</p>
     *
     * @param patient         患者（可 null，仅用于日志）
     * @param pid             患者 ID
     * @param bucketStartLocal Shanghai 时区的桶起始时间
     * @param source          该桶内选出的最佳 bloodSugar
     * @return true=upsert成功, false=现值相同SKIP
     */
    private boolean upsertBedsideForBucket(Patient patient, String pid,
                                           LocalDateTime bucketStartLocal, BloodSugar source) {
        String targetCode = bloodSugarSyncProperties.getTargetCode();
        String editUser = bloodSugarSyncProperties.getEditUser();

        // 计算 targetTime（与 sync/resyncBucket 一致）
        Date targetTime = Date.from(bucketStartLocal.atZone(ZONE).toInstant());

        Query targetQuery = new Query(Criteria.where("pid").is(pid)
                .and("code").is(targetCode)
                .and("time").is(targetTime));
        Bedside currentTarget = smartCareMongoTemplate.findOne(targetQuery, Bedside.class);

        String sourceStrVal = source.getResult();
        String sourceFVal = source.getResult();

        // 变动跟随：比较现值
        if (currentTarget != null
                && Objects.equals(currentTarget.getStrVal(), sourceStrVal)
                && Objects.equals(currentTarget.getFVal(), sourceFVal)) {
            log.debug("[BloodSugarSync] pid={}, targetTime={}: 现值相同 SKIP", pid, targetTime);
            return false;
        }

        // Upsert
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
                .set("synRemark", synRemark)
                .set("editUser", editUser)
                .set("editTime", new Date())
                .set("history", Collections.singletonList(syncHistory))
                .setOnInsert("_class", Bedside.BEDSIDE_CLASS);

        smartCareMongoTemplate.upsert(targetQuery, update, Bedside.class);

        Bedside upserted = smartCareMongoTemplate.findOne(targetQuery, Bedside.class);
        String generatedId = upserted != null ? upserted.getId() : null;

        // 写同步日志
        BedsideSyncLog syncLog = buildLog(BedsideSyncLog.TYPE_BLOODSUGAR, targetCode, patient,
                null, targetTime, generatedId, BedsideSyncLog.RESULT_SUCCESS,
                String.format("bloodSugarId=%s, sourceTime=%s, result=%s, bucket=%s",
                        source.getId(), source.getTime(), source.getResult(), bucketStartLocal));
        syncLogRepository.save(syncLog);

        log.info("[BloodSugarSync] ✓ pid={}, bucket={}, targetTime={}, result={}, bedsideId={}",
                pid, bucketStartLocal, targetTime, source.getResult(), generatedId);
        return true;
    }

    // ==================== 小时分桶取最新 ====================

    private Map<LocalDateTime, BloodSugar> selectBestPerHour(List<BloodSugar> records) {
        Map<LocalDateTime, BloodSugar> best = new LinkedHashMap<>();
        for (BloodSugar bs : records) {
            if (bs.getTime() == null) continue;
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

    /** 比较方法（同时用于 Comparator），返回负数表示 a 更新。 */
    private int compareBest(BloodSugar a, BloodSugar b) {
        if (a.getTime() == null || b.getTime() == null) {
            return a.getTime() != null ? 1 : -1;
        }
        int cmp = a.getTime().compareTo(b.getTime());
        if (cmp != 0) return cmp;
        String idA = a.getId();
        String idB = b.getId();
        if (idA != null && idB != null) return idA.compareTo(idB);
        return idA != null ? 1 : -1;
    }

    private boolean isNewer(BloodSugar a, BloodSugar b) {
        return compareBest(a, b) > 0;
    }

    // ==================== 日志工具方法 ====================

    private void saveFailLog(Patient patient, String code, String errorMsg, BloodSugar source) {
        BedsideSyncLog log = new BedsideSyncLog();
        log.setSyncType(BedsideSyncLog.TYPE_BLOODSUGAR);
        log.setCode(code);
        log.setPid(patient != null ? patient.getId() : null);
        log.setMrn(patient != null ? patient.getMrn() : null);
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
        log.setPid(patient != null ? patient.getId() : null);
        log.setMrn(patient != null ? patient.getMrn() : null);
        log.setSourceKey(sourceKey);
        log.setTargetTimePoint(targetTimePoint);
        log.setGeneratedBedsideId(generatedBedsideId);
        log.setResult(result);
        log.setMessage(message);
        log.setSyncTime(new Date());
        return log;
    }
}
