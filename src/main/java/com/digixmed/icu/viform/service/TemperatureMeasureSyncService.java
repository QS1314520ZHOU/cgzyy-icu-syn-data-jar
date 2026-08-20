package com.digixmed.icu.viform.service;

import com.digixmed.icu.viform.common.TimeUtils;
import com.digixmed.icu.viform.config.TubeNursingSyncProperties;
import com.digixmed.icu.viform.entity.Bedside;
import com.digixmed.icu.viform.entity.NurseRecords;
import com.digixmed.icu.viform.entity.NurseRecordsHistory;
import com.digixmed.icu.viform.entity.Patient;
import com.digixmed.icu.viform.repository.smartcare.BedsideRepository;
import com.digixmed.icu.viform.repository.smartcare.NurseRecordsHistoryRepository;
import com.digixmed.icu.viform.repository.smartcare.NurseRecordsRepository;
import com.digixmed.icu.viform.repository.smartcare.PatientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 降温/升温措施同步服务。
 *
 * <p>读取在院患者的降温措施（param_降温措施）和升温措施（param_升温措施） bedside 记录，
 * 同步到护理记录单（nurseRecords），同一时间点合并为一条记录。</p>
 *
 * <p>desc 格式：
 * <ul>
 *   <li>仅有降温：降温措施：{strVal}</li>
 *   <li>仅有升温：复温措施：{strVal}</li>
 *   <li>两者都有：降温措施：{strVal}\n复温措施：{strVal}</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemperatureMeasureSyncService {

    private final PatientRepository patientRepository;
    private final BedsideRepository bedsideRepository;
    private final NurseRecordsRepository nurseRecordsRepository;
    private final NurseRecordsHistoryRepository nurseRecordsHistoryRepository;
    private final TubeNursingSyncProperties properties;
    private final MongoTemplate smartCareMongoTemplate;

    /** 防重入锁 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 在院状态常量 */
    private static final String STATUS_ADMITTED = "admitted";

    /** 降温/升温措施编码 */
    private static final String CODE_COOLING = "param_降温措施";
    private static final String CODE_WARMING = "param_升温措施";
    private static final List<String> TEMP_MEASURE_CODES = Arrays.asList(CODE_COOLING, CODE_WARMING);

    /** 同步类型标识 */
    private static final String SYNC_TYPE = "TEMP_MEASURE";

    /** 同步结果统计 */
    public static class SyncResult {
        public final int totalPatients;
        public final int syncedRecords;
        public final int skippedRecords;
        public final int updatedRecords;
        public final int failedRecords;

        public SyncResult(int totalPatients, int syncedRecords, int skippedRecords,
                          int updatedRecords, int failedRecords) {
            this.totalPatients = totalPatients;
            this.syncedRecords = syncedRecords;
            this.skippedRecords = skippedRecords;
            this.updatedRecords = updatedRecords;
            this.failedRecords = failedRecords;
        }
    }

    /**
     * 执行全量同步。
     */
    public SyncResult syncAllAdmittedPatients() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[TempMeasureSync] 上一轮任务尚未完成，跳过本次");
            return new SyncResult(0, 0, 0, 0, 0);
        }

        AtomicInteger totalPatients = new AtomicInteger();
        AtomicInteger syncedRecords = new AtomicInteger();
        AtomicInteger skippedRecords = new AtomicInteger();
        AtomicInteger updatedRecords = new AtomicInteger();
        AtomicInteger failedRecords = new AtomicInteger();

        try {
            // 1. 查询在院患者
            List<Patient> patients = patientRepository.findByStatus(STATUS_ADMITTED);
            patients = patients.stream()
                    .filter(p -> StringUtils.hasText(p.getId()))
                    .collect(Collectors.toList());
            totalPatients.set(patients.size());
            log.info("[TempMeasureSync] 开始同步 admittedPatients={}", patients.size());

            if (patients.isEmpty()) {
                return new SyncResult(0, 0, 0, 0, 0);
            }

            // 构建 pid → patientName 映射
            Map<String, String> patientNameMap = new HashMap<>();
            for (Patient p : patients) {
                patientNameMap.put(p.getId(), p.getName());
            }

            // 2. 计算回溯时间
            Calendar syncCalendar = Calendar.getInstance();
            syncCalendar.add(Calendar.DAY_OF_MONTH, -properties.getSyncDays());
            Date syncStartTime = syncCalendar.getTime();

            // 3. 按批次处理患者
            int batchSize = properties.getBatchSize();
            List<List<String>> pidBatches = partition(
                    patients.stream().map(Patient::getId).collect(Collectors.toList()), batchSize);
            int totalBatches = pidBatches.size();
            log.info("[TempMeasureSync] 分批处理: 患者数={}, 批大小={}, 总批数={}",
                    patients.size(), batchSize, totalBatches);

            for (int i = 0; i < pidBatches.size(); i++) {
                List<String> batchPids = pidBatches.get(i);
                int batchNo = i + 1;
                log.info("[TempMeasureSync] 批次 {}/{} 开始, 患者数={}", batchNo, totalBatches, batchPids.size());

                try {
                    // 查询降温/升温措施记录
                    List<Bedside> tempRecords = bedsideRepository
                            .findByPidInAndCodeInAndTimeAfterAndValidTrue(
                                    batchPids, TEMP_MEASURE_CODES, syncStartTime);

                    if (tempRecords.isEmpty()) {
                        log.info("[TempMeasureSync] 批次 {}/{} 无数据，跳过", batchNo, totalBatches);
                        continue;
                    }

                    // 按 pid 分组
                    Map<String, List<Bedside>> recordsByPid = tempRecords.stream()
                            .collect(Collectors.groupingBy(Bedside::getPid));

                    // 按批次查询历史
                    List<NurseRecordsHistory> histories = nurseRecordsHistoryRepository
                            .findByPidInAndSyncType(batchPids, SYNC_TYPE);
                    Map<String, NurseRecordsHistory> historyMap = new HashMap<>();
                    for (NurseRecordsHistory history : histories) {
                        String key = buildHistoryKey(history.getPid(), history.getTubeRecordTime());
                        historyMap.put(key, history);
                    }

                    // 遍历处理
                    for (Map.Entry<String, List<Bedside>> entry : recordsByPid.entrySet()) {
                        String pid = entry.getKey();
                        String patientName = patientNameMap.getOrDefault(pid, "");

                        try {
                            processPatientRecords(pid, patientName, entry.getValue(),
                                    historyMap, syncedRecords, skippedRecords,
                                    updatedRecords, failedRecords);
                        } catch (Exception e) {
                            log.error("[TempMeasureSync] 处理患者异常 pid={}", pid, e);
                            failedRecords.incrementAndGet();
                        }
                    }

                    log.info("[TempMeasureSync] 批次 {}/{} 完成", batchNo, totalBatches);

                } catch (Exception e) {
                    log.error("[TempMeasureSync] 批次 {}/{} 异常", batchNo, totalBatches, e);
                }

                // 批间冷却
                if (batchNo < totalBatches) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            log.info("[TempMeasureSync] 完成 syncedRecords={} skippedRecords={} updatedRecords={} failedRecords={}",
                    syncedRecords.get(), skippedRecords.get(), updatedRecords.get(), failedRecords.get());

            return new SyncResult(totalPatients.get(), syncedRecords.get(), skippedRecords.get(),
                    updatedRecords.get(), failedRecords.get());

        } catch (Exception e) {
            log.error("[TempMeasureSync] 同步异常", e);
            return new SyncResult(totalPatients.get(), syncedRecords.get(), skippedRecords.get(),
                    updatedRecords.get(), failedRecords.get());
        } finally {
            running.set(false);
        }
    }

    /**
     * 处理单个患者的降温/升温措施记录。
     * <p>按分钟分组，同一分钟的降温+升温合并为一条 desc。</p>
     */
    private void processPatientRecords(String pid, String patientName,
                                        List<Bedside> records,
                                        Map<String, NurseRecordsHistory> historyMap,
                                        AtomicInteger synced,
                                        AtomicInteger skipped,
                                        AtomicInteger updated,
                                        AtomicInteger failed) {

        // 按分钟分组
        Map<String, List<Bedside>> byMinute = new LinkedHashMap<>();
        for (Bedside r : records) {
            if (r.getTime() == null || !StringUtils.hasText(r.getStrVal())) continue;
            Date minuteTime = TimeUtils.truncateToMinute(r.getTime());
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmm");
            String minuteKey = sdf.format(minuteTime);
            byMinute.computeIfAbsent(minuteKey, k -> new ArrayList<>()).add(r);
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmm");

        for (Map.Entry<String, List<Bedside>> entry : byMinute.entrySet()) {
            String minuteKey = entry.getKey();
            List<Bedside> minuteRecords = entry.getValue();

            try {
                // 构建 desc
                String desc = buildDesc(minuteRecords);
                if (!StringUtils.hasText(desc)) {
                    skipped.incrementAndGet();
                    continue;
                }

                // 取第一条记录的时间和操作人信息
                Bedside firstRecord = minuteRecords.get(0);
                Date minuteTime = firstRecord.getTime();
                String historyKey = pid + "_" + SYNC_TYPE + "_" + minuteKey;

                NurseRecordsHistory existingHistory = historyMap.get(historyKey);

                if (existingHistory != null) {
                    // 已有历史 → 检查是否需要更新
                    boolean contentSame = desc.equals(existingHistory.getSyncContent());
                    NurseRecords nurseRecord = existingHistory.getNurseRecordId() == null
                            ? null
                            : nurseRecordsRepository.findById(existingHistory.getNurseRecordId())
                                    .orElse(null);

                    if (nurseRecord == null) {
                        // 记录丢失，重建
                        NurseRecords newRecord = createNurseRecord(pid, patientName, desc, minuteTime);
                        NurseRecords saved = nurseRecordsRepository.insert(newRecord);
                        existingHistory.setNurseRecordId(saved.getId());
                        existingHistory.setSyncContent(desc);
                        existingHistory.setSyncTime(new Date());
                        nurseRecordsHistoryRepository.save(existingHistory);
                        synced.incrementAndGet();
                        continue;
                    }

                    if (contentSame) {
                        skipped.incrementAndGet();
                        continue;
                    }

                    // 内容变化 → 更新
                    nurseRecord.setDesc(desc);
                    nurseRecordsRepository.save(nurseRecord);
                    existingHistory.setSyncContent(desc);
                    existingHistory.setSyncTime(new Date());
                    nurseRecordsHistoryRepository.save(existingHistory);
                    updated.incrementAndGet();
                    log.info("[TempMeasureSync] 更新护理记录 pid={}, time={}", pid, minuteTime);
                } else {
                    // 无历史 → 检查同时间点是否已有管道/牙齿记录需要拼接
                    NurseRecords existingAtTime = findExistingAutoSynRecord(pid, minuteTime);

                    if (existingAtTime != null) {
                        // 已有记录 → 拼接
                        String oldDesc = existingAtTime.getDesc();
                        String mergedDesc = StringUtils.hasText(oldDesc)
                                ? oldDesc + "\n" + desc
                                : desc;
                        existingAtTime.setDesc(mergedDesc);
                        nurseRecordsRepository.save(existingAtTime);

                        NurseRecordsHistory newHistory = new NurseRecordsHistory();
                        newHistory.setPid(pid);
                        newHistory.setSyncType(SYNC_TYPE);
                        newHistory.setTubeRecordTime(minuteTime);
                        newHistory.setNurseRecordId(existingAtTime.getId());
                        newHistory.setSyncContent(desc);
                        newHistory.setSyncTime(new Date());
                        nurseRecordsHistoryRepository.insert(newHistory);
                        historyMap.put(historyKey, newHistory);

                        synced.incrementAndGet();
                        log.info("[TempMeasureSync] 拼接到已有护理记录 pid={}, nurseRecordId={}", pid, existingAtTime.getId());
                    } else {
                        // 无已有记录 → 新建
                        NurseRecords newRecord = createNurseRecord(pid, patientName, desc, minuteTime);
                        NurseRecords saved = nurseRecordsRepository.insert(newRecord);

                        NurseRecordsHistory newHistory = new NurseRecordsHistory();
                        newHistory.setPid(pid);
                        newHistory.setSyncType(SYNC_TYPE);
                        newHistory.setTubeRecordTime(minuteTime);
                        newHistory.setNurseRecordId(saved.getId());
                        newHistory.setSyncContent(desc);
                        newHistory.setSyncTime(new Date());
                        nurseRecordsHistoryRepository.insert(newHistory);
                        historyMap.put(historyKey, newHistory);

                        synced.incrementAndGet();
                        log.info("[TempMeasureSync] 新增护理记录 pid={}, time={}", pid, minuteTime);
                    }
                }
            } catch (Exception e) {
                log.error("[TempMeasureSync] 处理记录异常 pid={}, minuteKey={}", pid, minuteKey, e);
                failed.incrementAndGet();
            }
        }
    }

    /**
     * 构建降温/升温措施的 desc。
     * <p>同一分钟可能同时有降温措施和升温措施，合并为一条。</p>
     */
    private String buildDesc(List<Bedside> records) {
        StringBuilder sb = new StringBuilder();
        for (Bedside r : records) {
            String val = r.getStrVal().trim();
            if (!StringUtils.hasText(val)) continue;

            if (CODE_COOLING.equals(r.getCode())) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("降温措施：").append(val);
            } else if (CODE_WARMING.equals(r.getCode())) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("复温措施：").append(val);
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 查找指定患者在同一分钟已有的自动同步护理记录。
     */
    private NurseRecords findExistingAutoSynRecord(String pid, Date minuteTime) {
        Date start = minuteTime;
        Date end = new Date(minuteTime.getTime() + 60_000);
        List<NurseRecords> records = nurseRecordsRepository
                .findByPidAndAutoSynTrueAndTimeBetween(pid, start, end);
        return records.isEmpty() ? null : records.get(0);
    }

    /**
     * 构建历史记录唯一键。
     */
    private String buildHistoryKey(String pid, Date recordTime) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmm");
        return pid + "_" + SYNC_TYPE + "_" + sdf.format(recordTime);
    }

    /**
     * 创建护理记录对象。
     */
    private NurseRecords createNurseRecord(String pid, String patientName,
                                            String desc, Date time) {
        NurseRecords nurseRecord = new NurseRecords();
        nurseRecord.setPid(pid);
        nurseRecord.setName(patientName);
        nurseRecord.setDesc(desc);
        nurseRecord.setTime(TimeUtils.truncateToMinute(time));
        nurseRecord.setCreateTime(new Date());
        nurseRecord.setValid(true);
        nurseRecord.setAutoSyn(true);
        nurseRecord.setUseTimes(0);
        nurseRecord.setDrugExeManualFlag(false);
        return nurseRecord;
    }

    /**
     * 将列表按指定大小分组。
     */
    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            result.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return result;
    }
}
