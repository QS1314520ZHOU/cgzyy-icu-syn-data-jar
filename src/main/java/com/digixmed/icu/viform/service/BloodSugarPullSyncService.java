package com.digixmed.icu.viform.service;

import com.digixmed.icu.viform.config.BloodSugarPullProperties;
import com.digixmed.icu.viform.dto.BloodSugarViewDTO;
import com.digixmed.icu.viform.entity.Account;
import com.digixmed.icu.viform.entity.BloodSugar;
import com.digixmed.icu.viform.entity.Patient;
import com.digixmed.icu.viform.repository.kingbase.KingbaseBloodSugarViewRepository;
import com.digixmed.icu.viform.repository.smartcare.AccountRepository;
import com.digixmed.icu.viform.repository.smartcare.BloodSugarRepository;
import com.digixmed.icu.viform.repository.smartcare.PatientRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 血糖拉取同步服务 —— 从人大金仓视图 v_blood_for_zzxt 定时拉取血糖数据写入 bloodSugar 集合。
 *
 * <p>核心逻辑：在科患者的 mrn ↔ 视图住院号匹配 → 24h 回溯窗口查视图 →
 * 显式字段映射 → (pid, time) 防重写入。5 分钟一轮，靠防重保证幂等。</p>
 *
 * <p>specimenSource 固定「末梢血」；examinerId 用签名匹配 account.trueName 取 account.id；
 * 数值 "Hi" 等仪器标记值原样存入 result。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BloodSugarPullSyncService {

    private final PatientRepository patientRepository;
    private final BloodSugarRepository bloodSugarRepository;
    private final AccountRepository accountRepository;
    private final KingbaseBloodSugarViewRepository kingbaseBloodSugarViewRepository;
    private final BloodSugarPullProperties properties;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 标本来源固定默认值 */
    private static final String SPECIMEN_SOURCE = "末梢血";

    /** 设备编码固定默认值 */
    private static final String DEVICE_CODE = "";

    // ==================== 同步主流程 ====================

    /**
     * 执行一轮同步（lookbackHours 回溯窗口内）。
     */
    public SyncResult sync() {
        long nowMillis = System.currentTimeMillis() / 1000 * 1000;
        Date end = new Date(nowMillis);
        Date start = new Date(nowMillis - properties.getLookbackHours() * 3600_000L);
        return run(start, end);
    }

    /**
     * 全量补拉：在科患者全部历史血糖数据（不受 lookbackHours 限制）。
     *
     * <p>用于一次性补历史数据（如患者住院超过 24h、早期数据未拉到），不参与定时调度。
     * 按 (pid, time) 防重，可重复调用。</p>
     */
    public SyncResult syncFull() {
        Date end = new Date(System.currentTimeMillis() / 1000 * 1000);
        // epoch 起点 = 不限起始时间
        return run(new Date(0), end);
    }

    private SyncResult run(Date start, Date end) {
        if (!running.compareAndSet(false, true)) {
            log.info("[BloodSugarPull] 上一轮尚未结束，跳过");
            return SyncResult.skipped();
        }
        try {
            return doSync(start, end);
        } catch (Exception e) {
            log.error("[BloodSugarPull] 同步异常", e);
            return SyncResult.failed();
        } finally {
            running.set(false);
        }
    }

    private SyncResult doSync(Date start, Date end) {
        SyncResult result = new SyncResult();

        // 1. 在科患者（status=admitted 且 icuAdmissionTime≠null 且有 mrn）
        List<Patient> patients = patientRepository.findByStatus("admitted").stream()
                .filter(p -> p.getIcuAdmissionTime() != null && StringUtils.hasText(p.getMrn()))
                .collect(Collectors.toList());
        if (patients.isEmpty()) {
            log.info("[BloodSugarPull] 无在科患者");
            return result;
        }

        // 2. 住院号索引（精确优先 + 去前导 0 兜底）
        Map<String, Patient> byMrnExact = new HashMap<>();
        Map<String, Patient> byMrnTrimmed = new HashMap<>();
        for (Patient p : patients) {
            String mrn = p.getMrn().trim();
            byMrnExact.putIfAbsent(mrn, p);
            String trimmed = stripLeadingZeros(mrn);
            if (byMrnTrimmed.putIfAbsent(trimmed, p) != null && !byMrnTrimmed.get(trimmed).getId().equals(p.getId())) {
                log.warn("[BloodSugarPull] 去前导0后 mrn 冲突: mrn={}, trimmed={}, 已有={}, 新来={}",
                        mrn, trimmed, byMrnTrimmed.get(trimmed).getId(), p.getId());
            }
        }
        List<String> mrnList = patients.stream()
                .map(p -> p.getMrn().trim())
                .distinct()
                .collect(Collectors.toList());

        // 3. 查视图（时间窗由调用方给定；内部分批 IN，异常返回空集合）
        List<BloodSugarViewDTO> viewRows =
                kingbaseBloodSugarViewRepository.findByInhosNosAndTimeWindow(mrnList, start, end);
        result.totalRows = viewRows.size();
        if (viewRows.isEmpty()) {
            log.info("[BloodSugarPull] 视图无数据，mrns={}, window=[{}, {}]", mrnList.size(), start, end);
            return result;
        }

        // 4. 行 → 预映射
        List<BloodSugar> candidates = new ArrayList<>();
        Map<String, String> examinerCache = new HashMap<>();
        for (BloodSugarViewDTO row : viewRows) {
            Patient p = lookupPatient(byMrnExact, byMrnTrimmed, row.getInhosNo());
            if (p == null) {
                result.unmatchedInhosNo++;
                if (result.unmatchedInhosNo <= 5) {
                    log.info("[BloodSugarPull] 未匹配住院号: inhosNo={}", row.getInhosNo());
                }
                continue;
            }
            if (!StringUtils.hasText(row.getValue())) {
                result.invalidRows++;
                continue;
            }
            BloodSugar bs = new BloodSugar();
            bs.setPid(p.getId());
            bs.setTime(row.getRecordTime());
            bs.setResult(row.getValue().trim());
            bs.setDetectionProject(row.getType() != null ? row.getType().trim() : null);
            String signer = row.getSigner() != null ? row.getSigner().trim() : null;
            bs.setExaminer(signer);
            bs.setExaminerId(resolveExaminerIdCached(signer, examinerCache));
            bs.setRemarks(row.getNote() != null ? row.getNote().trim() : null);
            bs.setSpecimenSource(SPECIMEN_SOURCE);
            bs.setDeviceCode(DEVICE_CODE);
            bs.setValid(true);
            candidates.add(bs);
            result.matched++;
        }

        // 5. 批量预取防重索引
        Set<String> pids = candidates.stream().map(BloodSugar::getPid).collect(Collectors.toSet());
        Map<String, Map<Long, BloodSugar>> index = new HashMap<>();
        if (!pids.isEmpty()) {
            List<BloodSugar> existingList =
                    bloodSugarRepository.findByPidInAndValidTrueAndTimeGreaterThanEqual(pids, start);
            for (BloodSugar e : existingList) {
                index.computeIfAbsent(e.getPid(), k -> new HashMap<>())
                        .putIfAbsent(e.getTime().getTime(), e);
            }
        }

        // 6. 防重写入
        Set<String> seenInRun = new HashSet<>();
        for (BloodSugar cand : candidates) {
            String runKey = cand.getPid() + "|" + cand.getTime().getTime();
            if (!seenInRun.add(runKey)) {
                result.dupRows++;
                continue;
            }
            BloodSugar cur = index
                    .getOrDefault(cand.getPid(), Collections.emptyMap())
                    .get(cand.getTime().getTime());
            if (cur == null) {
                bloodSugarRepository.save(cand);
                result.inserted++;
            } else if (fieldsChanged(cur, cand)) {
                applyFields(cur, cand);
                bloodSugarRepository.save(cur);
                result.updated++;
            } else {
                result.skipped++;
            }
        }

        log.info("[BloodSugarPull] 同步完成: {}", result);
        return result;
    }

    // ==================== 字段比对与写入 ====================

    /** 8 个可写字段全等比对（pid/time 是键，不参与比对） */
    private boolean fieldsChanged(BloodSugar cur, BloodSugar cand) {
        return !Objects.equals(cur.getResult(), cand.getResult())
                || !Objects.equals(cur.getDetectionProject(), cand.getDetectionProject())
                || !Objects.equals(cur.getExaminer(), cand.getExaminer())
                || !Objects.equals(cur.getExaminerId(), cand.getExaminerId())
                || !Objects.equals(cur.getRemarks(), cand.getRemarks())
                || !Objects.equals(cur.getSpecimenSource(), cand.getSpecimenSource())
                || !Objects.equals(cur.getDeviceCode(), cand.getDeviceCode())
                || !Objects.equals(cur.getValid(), cand.getValid());
    }

    /** 显式覆盖 8 个可写字段（不改 pid 与 time） */
    private void applyFields(BloodSugar target, BloodSugar source) {
        target.setResult(source.getResult());
        target.setDetectionProject(source.getDetectionProject());
        target.setExaminer(source.getExaminer());
        target.setExaminerId(source.getExaminerId());
        target.setRemarks(source.getRemarks());
        target.setSpecimenSource(source.getSpecimenSource());
        target.setDeviceCode(source.getDeviceCode());
        target.setValid(source.getValid());
    }

    // ==================== 内部工具 ====================

    /** 住院号查找：精确优先，去前导 0 兜底 */
    private Patient lookupPatient(Map<String, Patient> exact, Map<String, Patient> trimmed, String inhosNo) {
        if (!StringUtils.hasText(inhosNo)) return null;
        Patient p = exact.get(inhosNo);
        if (p != null) return p;
        return trimmed.get(stripLeadingZeros(inhosNo));
    }

    /** 去掉前导 0（全 0 时返回 "0"） */
    private static String stripLeadingZeros(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') i++;
        return s.substring(i);
    }

    /**
     * 用签名匹配 account.trueName，取 account.id（带本轮内存缓存）。
     * 查不到返回 null，不阻断。
     */
    private String resolveExaminerIdCached(String signer, Map<String, String> cache) {
        if (!StringUtils.hasText(signer)) return null;
        return cache.computeIfAbsent(signer, name -> {
            Optional<Account> acc = accountRepository.findFirstByTrueName(name);
            if (acc.isPresent()) {
                return acc.get().getId();
            }
            log.info("[BloodSugarPull] 未匹配到 examinerId: signer={}", name);
            return null;
        });
    }

    // ==================== 统计结果 ====================

    /** 同步统计 */
    @Data
    public static class SyncResult {
        int totalRows;
        int matched;
        int inserted;
        int updated;
        int skipped;
        int dupRows;
        int unmatchedInhosNo;
        int invalidRows;
        int failed;
        boolean skippedRun;

        static SyncResult skipped() {
            SyncResult r = new SyncResult();
            r.skippedRun = true;
            return r;
        }

        static SyncResult failed() {
            SyncResult r = new SyncResult();
            r.failed = 1;
            return r;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("totalRows", totalRows);
            m.put("matched", matched);
            m.put("inserted", inserted);
            m.put("updated", updated);
            m.put("skipped", skipped);
            m.put("dupRows", dupRows);
            m.put("unmatchedInhosNo", unmatchedInhosNo);
            m.put("invalidRows", invalidRows);
            m.put("failed", failed);
            m.put("skippedRun", skippedRun);
            return m;
        }

        @Override
        public String toString() {
            return "totalRows=" + totalRows + ", matched=" + matched
                    + ", inserted=" + inserted + ", updated=" + updated
                    + ", skipped=" + skipped + ", dupRows=" + dupRows
                    + ", unmatchedInhosNo=" + unmatchedInhosNo + ", invalidRows=" + invalidRows
                    + ", failed=" + failed + ", skippedRun=" + skippedRun;
        }
    }
}
