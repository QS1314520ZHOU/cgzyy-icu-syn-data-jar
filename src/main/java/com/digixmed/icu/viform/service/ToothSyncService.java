package com.digixmed.icu.viform.service;

import com.digixmed.icu.viform.common.TimeUtils;
import com.digixmed.icu.viform.config.TubeNursingSyncProperties;
import com.digixmed.icu.viform.entity.Account;
import com.digixmed.icu.viform.entity.Bedside;
import com.digixmed.icu.viform.entity.NurseRecords;
import com.digixmed.icu.viform.entity.NurseRecordsHistory;
import com.digixmed.icu.viform.entity.Patient;
import com.digixmed.icu.viform.repository.smartcare.AccountRepository;
import com.digixmed.icu.viform.repository.smartcare.BedsideRepository;
import com.digixmed.icu.viform.repository.smartcare.NurseRecordsHistoryRepository;
import com.digixmed.icu.viform.repository.smartcare.NurseRecordsRepository;
import com.digixmed.icu.viform.repository.smartcare.PatientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 牙齿管理数据同步服务。
 *
 * <p>读取在院患者的牙齿评估数据（bedside.code=param_yaChi），
 * 同步到护理记录单（nurseRecords），并通过 nurseRecordsHistory 实现去重和覆盖更新。</p>
 *
 * <p>核心策略：</p>
 * <ul>
 *   <li>不分班次，有数据就同步</li>
 *   <li>通过 nurseRecordsHistory 实现去重</li>
 *   <li>内容变化时覆盖更新</li>
 *   <li>同步时间范围由 tube-nursing-sync.sync-days 配置控制</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToothSyncService {

    private final PatientRepository patientRepository;
    private final BedsideRepository bedsideRepository;
    private final NurseRecordsRepository nurseRecordsRepository;
    private final NurseRecordsHistoryRepository nurseRecordsHistoryRepository;
    private final AccountRepository accountRepository;
    private final TubeNursingSyncProperties properties;

    /** 防重入锁 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 在院状态常量 */
    private static final String STATUS_ADMITTED = "admitted";

    /** 牙齿评估编码 */
    private static final String TOOTH_CODE = "param_yaChi";

    /** 同步类型标识 */
    private static final String SYNC_TYPE = "TOOTH";

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
     * 执行全量同步（按患者分批处理，降低数据库压力）。
     */
    public SyncResult syncAllAdmittedPatients() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[ToothSync] 上一轮任务尚未完成，跳过本次");
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
            log.info("[ToothSync] 开始同步 admittedPatients={}", patients.size());

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
            log.info("[ToothSync] 分批处理: 患者数={}, 批大小={}, 总批数={}",
                    patients.size(), batchSize, totalBatches);

            for (int i = 0; i < pidBatches.size(); i++) {
                List<String> batchPids = pidBatches.get(i);
                int batchNo = i + 1;
                log.info("[ToothSync] 批次 {}/{} 开始, 患者数={}", batchNo, totalBatches, batchPids.size());

                try {
                    // 按批次查询牙齿评估数据
                    List<Bedside> toothRecords = bedsideRepository.findByPidInAndCodeAndTimeAfter(
                            batchPids, TOOTH_CODE, syncStartTime);

                    if (toothRecords.isEmpty()) {
                        log.info("[ToothSync] 批次 {}/{} bedside 无数据，跳过", batchNo, totalBatches);
                        continue;
                    }

                    // 批量查询账户信息
                    Set<String> editUserIds = toothRecords.stream()
                            .map(Bedside::getEditUser)
                            .filter(StringUtils::hasText)
                            .collect(Collectors.toSet());
                    Map<String, String> accountNameMap = new HashMap<>();
                    if (!editUserIds.isEmpty()) {
                        List<Account> accounts = accountRepository.findByIdIn(editUserIds);
                        for (Account account : accounts) {
                            if (StringUtils.hasText(account.getTrueName())) {
                                accountNameMap.put(account.getId(), account.getTrueName());
                            }
                        }
                    }

                    // 按批次查询历史
                    List<NurseRecordsHistory> histories = nurseRecordsHistoryRepository.findByPidInAndSyncType(
                            batchPids, SYNC_TYPE);
                    Map<String, NurseRecordsHistory> historyMap = new HashMap<>();
                    for (NurseRecordsHistory history : histories) {
                        String key = buildHistoryKey(history.getPid(), history.getTubeRecordTime());
                        historyMap.put(key, history);
                    }

                    // 遍历处理每条牙齿记录
                    for (Bedside record : toothRecords) {
                        String pid = record.getPid();
                        if (!StringUtils.hasText(pid)) continue;
                        String patientName = patientNameMap.getOrDefault(pid, "");

                        try {
                            String strVal = record.getStrVal();
                            if (!StringUtils.hasText(strVal)) {
                                skippedRecords.incrementAndGet();
                                continue;
                            }

                            Date recordTime = record.getTime();
                            String editUserId = record.getEditUser();
                            String editUserName = accountNameMap.getOrDefault(editUserId, "");

                            String historyKey = buildHistoryKey(pid, recordTime);
                            NurseRecordsHistory existingHistory = historyMap.get(historyKey);

                            if (existingHistory != null) {
                                Date minuteTime = TimeUtils.truncateToMinute(recordTime);
                                boolean contentSame = strVal.equals(existingHistory.getSyncContent());

                                NurseRecords nurseRecord = existingHistory.getNurseRecordId() == null
                                        ? null
                                        : nurseRecordsRepository.findById(existingHistory.getNurseRecordId())
                                                .orElse(null);

                                if (nurseRecord == null) {
                                    NurseRecords newRecord = createNurseRecord(pid, patientName,
                                            editUserName, editUserId, record, strVal);
                                    NurseRecords saved = nurseRecordsRepository.insert(newRecord);

                                    existingHistory.setNurseRecordId(saved.getId());
                                    existingHistory.setSyncContent(strVal);
                                    existingHistory.setSyncTime(new Date());
                                    nurseRecordsHistoryRepository.save(existingHistory);

                                    syncedRecords.incrementAndGet();
                                    continue;
                                }

                                boolean timeSame = Objects.equals(nurseRecord.getTime(), minuteTime);

                                if (contentSame && timeSame) {
                                    skippedRecords.incrementAndGet();
                                    continue;
                                }

                                nurseRecord.setDesc(strVal);
                                nurseRecord.setTime(minuteTime);
                                nurseRecord.setUsername(editUserName);
                                nurseRecordsRepository.save(nurseRecord);

                                existingHistory.setSyncContent(strVal);
                                existingHistory.setSyncTime(new Date());
                                nurseRecordsHistoryRepository.save(existingHistory);

                                updatedRecords.incrementAndGet();
                                log.info("[ToothSync] 更新牙齿护理记录 pid={}, 内容变更={}, 时间变更={}",
                                        pid, !contentSame, !timeSame);
                            } else {
                                NurseRecords newRecord = createNurseRecord(pid, patientName,
                                        editUserName, editUserId, record, strVal);
                                NurseRecords saved = nurseRecordsRepository.insert(newRecord);

                                NurseRecordsHistory newHistory = new NurseRecordsHistory();
                                newHistory.setPid(pid);
                                newHistory.setSyncType(SYNC_TYPE);
                                newHistory.setTubeExeId(record.getId());
                                newHistory.setTubeType(SYNC_TYPE);
                                newHistory.setShiftType("");
                                newHistory.setTubeRecordTime(recordTime);
                                newHistory.setNurseRecordId(saved.getId());
                                newHistory.setSyncTime(new Date());
                                newHistory.setSyncContent(strVal);
                                nurseRecordsHistoryRepository.insert(newHistory);

                                syncedRecords.incrementAndGet();
                                log.info("[ToothSync] 新增牙齿护理记录 pid={}", pid);
                            }
                        } catch (Exception e) {
                            log.error("[ToothSync] 同步牙齿记录异常 pid={}", pid, e);
                            failedRecords.incrementAndGet();
                        }
                    }

                    log.info("[ToothSync] 批次 {}/{} 完成", batchNo, totalBatches);

                } catch (Exception e) {
                    log.error("[ToothSync] 批次 {}/{} 异常", batchNo, totalBatches, e);
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

            log.info("[ToothSync] 完成 syncedRecords={} skippedRecords={} updatedRecords={} failedRecords={}",
                    syncedRecords.get(), skippedRecords.get(), updatedRecords.get(), failedRecords.get());

            return new SyncResult(totalPatients.get(), syncedRecords.get(), skippedRecords.get(),
                    updatedRecords.get(), failedRecords.get());

        } catch (Exception e) {
            log.error("[ToothSync] 同步异常", e);
            return new SyncResult(totalPatients.get(), syncedRecords.get(), skippedRecords.get(),
                    updatedRecords.get(), failedRecords.get());
        } finally {
            running.set(false);
        }
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
                                            String editUserName, String editUserId,
                                            Bedside record, String desc) {
        NurseRecords nurseRecord = new NurseRecords();
        nurseRecord.setPid(pid);
        nurseRecord.setName(patientName);
        nurseRecord.setUsername(editUserName);
        nurseRecord.setUserId(editUserId);
        nurseRecord.setDesc(desc);
        nurseRecord.setTime(TimeUtils.truncateToMinute(record.getTime()));
        nurseRecord.setCreateTime(new Date());
        nurseRecord.setValid(true);
        nurseRecord.setAutoSyn(true);
        nurseRecord.setUseTimes(0);
        nurseRecord.setDrugExeManualFlag(false);
        return nurseRecord;
    }
}
