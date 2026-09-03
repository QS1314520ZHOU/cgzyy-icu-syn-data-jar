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

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * 危重患者转运评分数据同步服务。
 *
 * <p>读取在院患者的转运评分数据（bedside.code=param_score_criticalPatientTransferScore），
 * 同步到护理记录单（nurseRecords），并通过 nurseRecordsHistory 实现去重和覆盖更新。</p>
 *
 * <p>核心策略：</p>
 * <ul>
 *   <li>不分班次，有数据就同步</li>
 *   <li>通过 nurseRecordsHistory 实现去重</li>
 *   <li>内容变化时覆盖更新</li>
 *   <li>同步时间范围由 tube-nursing-sync.sync-days 配置控制</li>
 *   <li>只同步 valid=true 的记录</li>
 *   <li>数据转换规则：包含"-"按分隔符解析，否则根据是否包含"级"判断字段</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferScoreSyncService {

    private final PatientRepository patientRepository;
    private final BedsideRepository bedsideRepository;
    private final NurseRecordsRepository nurseRecordsRepository;
    private final NurseRecordsHistoryRepository nurseRecordsHistoryRepository;
    private final AccountRepository accountRepository;
    private final TubeNursingSyncProperties properties;
    private final MongoTemplate smartCareMongoTemplate;

    /** 防重入锁 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 在院状态常量 */
    private static final String STATUS_ADMITTED = "admitted";

    /** 转运评分编码 */
    private static final String TRANSFER_SCORE_CODE = "param_score_criticalPatientTransferScore";

    /** 同步类型标识 */
    private static final String SYNC_TYPE = "TRANSFER_SCORE";

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
            log.warn("[TransferScoreSync] 上一轮任务尚未完成，跳过本次");
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
            log.info("[TransferScoreSync] 开始同步 admittedPatients={}", patients.size());

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
            log.info("[TransferScoreSync] 分批处理: 患者数={}, 批大小={}, 总批数={}",
                    patients.size(), batchSize, totalBatches);

            for (int i = 0; i < pidBatches.size(); i++) {
                List<String> batchPids = pidBatches.get(i);
                int batchNo = i + 1;
                log.info("[TransferScoreSync] 批次 {}/{} 开始, 患者数={}", batchNo, totalBatches, batchPids.size());

                try {
                    // 按批次查询转运评分数据
                    List<Bedside> transferRecords = bedsideRepository.findByPidInAndCodeAndTimeAfter(
                            batchPids, TRANSFER_SCORE_CODE, syncStartTime);

                    // 过滤 valid=true 的记录
                    transferRecords = transferRecords.stream()
                            .filter(r -> Boolean.TRUE.equals(r.getValid()))
                            .collect(Collectors.toList());

                    if (transferRecords.isEmpty()) {
                        log.info("[TransferScoreSync] 批次 {}/{} bedside 无有效数据，跳过", batchNo, totalBatches);
                        continue;
                    }

                    // 批量查询账户信息
                    Set<String> editUserIds = transferRecords.stream()
                            .map(Bedside::getEditUser)
                            .filter(StringUtils::hasText)
                            .collect(Collectors.toSet());
                    Map<String, Account> accountMap = new HashMap<>();
                    if (!editUserIds.isEmpty()) {
                        List<Account> accounts = accountRepository.findByIdIn(editUserIds);
                        for (Account account : accounts) {
                            accountMap.put(account.getId(), account);
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

                    // 遍历处理每条转运评分记录
                    for (Bedside record : transferRecords) {
                        String pid = record.getPid();
                        if (!StringUtils.hasText(pid)) continue;
                        String patientName = patientNameMap.getOrDefault(pid, "");

                        try {
                            String strVal = record.getStrVal();
                            if (!StringUtils.hasText(strVal)) {
                                skippedRecords.incrementAndGet();
                                continue;
                            }

                            // 转换数据格式
                            String desc = convertTransferScoreData(strVal);
                            if (!StringUtils.hasText(desc)) {
                                skippedRecords.incrementAndGet();
                                log.info("[TransferScoreSync] 数据转换后为空，跳过 pid={}, strVal={}", pid, strVal);
                                continue;
                            }

                            Date recordTime = record.getTime();
                            String editUserId = record.getEditUser();
                            Account editAccount = accountMap.get(editUserId);
                            String editUserName = editAccount != null ? editAccount.getTrueName() : "";
                            String accountUsername = editAccount != null ? editAccount.getUsername() : "";
                            String accountProfession = editAccount != null ? editAccount.getProfession() : "";

                            String historyKey = buildHistoryKey(pid, recordTime);
                            NurseRecordsHistory existingHistory = historyMap.get(historyKey);

                            // 核心逻辑：日志表有记录 = 已同步过 = 直接跳过
                            if (existingHistory != null) {
                                skippedRecords.incrementAndGet();
                                log.info("[TransferScoreSync] 已同步过，跳过 pid={}, time={}", pid, recordTime);
                                continue;
                            }

                            {
                                // 检查同一时间点是否已有记录（可能是用户手写的，也可能是其他同步的）
                                Date minuteTime = TimeUtils.truncateToMinute(recordTime);
                                NurseRecords existingAtTime = findExistingAutoSynRecord(pid, minuteTime);

                                if (existingAtTime != null) {
                                    // 检查是否是用户手写的
                                    if (isUserWritten(existingAtTime)) {
                                        // [修改记录] 2026-08-22 易绍龙: 用户手写记录 → 始终追加，不跳过
                                        appendToExistingRecord(existingAtTime, desc, record, pid);
                                        syncedRecords.incrementAndGet();
                                        log.info("[TransferScoreSync] 追加转运评分数据到用户记录 pid={}, nurseRecordId={}", pid, existingAtTime.getId());
                                    } else {
                                        // 其他同步记录 → 转运评分数据拼接到已有记录后面
                                        String oldDesc = existingAtTime.getDesc();
                                        String mergedDesc = StringUtils.hasText(oldDesc)
                                                ? oldDesc + "\n" + desc
                                                : desc;
                                        existingAtTime.setDesc(mergedDesc);
                                        nurseRecordsRepository.save(existingAtTime);

                                        // 更新或创建转运评分 history
                                        NurseRecordsHistory existingPipeHistory = findHistoryByNurseRecordId(
                                                existingAtTime.getId());
                                        if (existingPipeHistory != null) {
                                            existingPipeHistory.setSyncContent(mergedDesc);
                                            existingPipeHistory.setSyncTime(new Date());
                                            nurseRecordsHistoryRepository.save(existingPipeHistory);
                                        }

                                        // 创建转运评分 history 记录
                                        NurseRecordsHistory newHistory = new NurseRecordsHistory();
                                        newHistory.setPid(pid);
                                        newHistory.setSyncType(SYNC_TYPE);
                                        newHistory.setTubeExeId(record.getId());
                                        newHistory.setTubeType(SYNC_TYPE);
                                        newHistory.setShiftType("");
                                        newHistory.setTubeRecordTime(recordTime);
                                        newHistory.setNurseRecordId(existingAtTime.getId());
                                        newHistory.setSyncTime(new Date());
                                        newHistory.setSyncContent(desc);
                                        nurseRecordsHistoryRepository.insert(newHistory);

                                        syncedRecords.incrementAndGet();
                                        log.info("[TransferScoreSync] 转运评分数据拼接到同步记录 pid={}, nurseRecordId={}", pid, existingAtTime.getId());
                                    }
                                } else {
                                    // 无已有记录 → 新建（标记为自动同步）
                                    NurseRecords newRecord = createNurseRecord(pid, patientName,
                                            editUserName, editUserId, record, desc,
                                            accountUsername, accountProfession);
                                    newRecord.setAutoSyn(true);  // 标记为自动同步
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
                                    newHistory.setSyncContent(desc);
                                    nurseRecordsHistoryRepository.insert(newHistory);

                                    syncedRecords.incrementAndGet();
                                    log.info("[TransferScoreSync] 新增同步护理记录 pid={}", pid);
                                }
                            }
                        } catch (Exception e) {
                            log.error("[TransferScoreSync] 同步转运评分记录异常 pid={}", pid, e);
                            failedRecords.incrementAndGet();
                        }
                    }

                    log.info("[TransferScoreSync] 批次 {}/{} 完成", batchNo, totalBatches);

                } catch (Exception e) {
                    log.error("[TransferScoreSync] 批次 {}/{} 异常", batchNo, totalBatches, e);
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

            log.info("[TransferScoreSync] 完成 syncedRecords={} skippedRecords={} updatedRecords={} failedRecords={}",
                    syncedRecords.get(), skippedRecords.get(), updatedRecords.get(), failedRecords.get());

            return new SyncResult(totalPatients.get(), syncedRecords.get(), skippedRecords.get(),
                    updatedRecords.get(), failedRecords.get());

        } catch (Exception e) {
            log.error("[TransferScoreSync] 同步异常", e);
            return new SyncResult(totalPatients.get(), syncedRecords.get(), skippedRecords.get(),
                    updatedRecords.get(), failedRecords.get());
        } finally {
            running.set(false);
        }
    }

    /**
     * 转换转运评分数据格式。
     *
     * <p>转换规则：</p>
     * <ul>
     *   <li>包含"-"：按"-"分隔，拼接为"转运分级标准:+[0],MEWS评分:+[1]"</li>
     *   <li>不包含"-"：
     *     <ul>
     *       <li>包含"级"：展示"转运分级标准:+strVal"</li>
     *       <li>不包含"级"：展示"MEWS评分:+strVal"</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * @param strVal 原始数据
     * @return 转换后的描述
     */
    private String convertTransferScoreData(String strVal) {
        if (!StringUtils.hasText(strVal)) {
            return "";
        }

        strVal = strVal.trim();

        // 包含"-"的情况：按"-"分隔
        if (strVal.contains("-")) {
            String[] parts = strVal.split("-", 2);
            if (parts.length >= 2) {
                String transferLevel = parts[0].trim();
                String mewsScore = parts[1].trim();
                return "转运分级标准:" + transferLevel + ",MEWS评分:" + mewsScore;
            }
        }

        // 不包含"-"的情况：根据是否包含"级"判断
        if (strVal.contains("级")) {
            return "转运分级标准:" + strVal;
        } else {
            return "MEWS评分:" + strVal;
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
                                            Bedside record, String desc,
                                            String accountUsername, String accountProfession) {
        NurseRecords nurseRecord = new NurseRecords();
        nurseRecord.setPid(pid);
        nurseRecord.setName(patientName);
        nurseRecord.setUsername(editUserName);
        nurseRecord.setUserId(editUserId);
        nurseRecord.setTrueName(accountUsername);
        nurseRecord.setProfessions(accountProfession);
        nurseRecord.setDesc(desc);
        nurseRecord.setTime(TimeUtils.truncateToMinute(record.getTime()));
        nurseRecord.setCreateTime(new Date());
        nurseRecord.setValid(true);
        nurseRecord.setUseTimes(0);
        nurseRecord.setDrugExeManualFlag(false);
        nurseRecord.setAutoSyn(false);
        return nurseRecord;
    }

    /**
     * 查找指定患者在同一时间点已有的护理记录（管道或转运评分）。
     * 精确匹配年月日时分，不使用时间范围。
     */
    private NurseRecords findExistingAutoSynRecord(String pid, Date minuteTime) {
        // 截断到分钟开始
        Date start = TimeUtils.truncateToMinute(minuteTime);
        // 截断到分钟结束（下一分钟）
        Date end = new Date(start.getTime() + 60_000);
        List<NurseRecords> records = nurseRecordsRepository.findByPidAndTimeBetween(pid, start, end);
        return records.isEmpty() ? null : records.get(0);
    }

    /**
     * 根据 nurseRecordId 查找对应的同步历史。
     */
    private NurseRecordsHistory findHistoryByNurseRecordId(String nurseRecordId) {
        Query query = new Query(Criteria.where("nurseRecordId").is(nurseRecordId));
        return smartCareMongoTemplate.findOne(query, NurseRecordsHistory.class);
    }

    /**
     * 判断是否是用户手写的记录。
     *
     * @param record 护理记录
     * @return true=用户手写，false=自动同步
     */
    private boolean isUserWritten(NurseRecords record) {
        // 用户手写的判断条件：
        // 1. autoSyn = null 或 false（非自动同步）
        // 2. 或者 username 不是系统账号
        return !Boolean.TRUE.equals(record.getAutoSyn())
               || !isSystemAccount(record.getUsername());
    }

    /**
     * 判断是否是系统账号。
     *
     * @param username 用户名
     * @return true=系统账号，false=用户账号
     */
    private boolean isSystemAccount(String username) {
        // 系统账号判断（根据实际配置的系统账号）
        return "系统同步".equals(username)
               || "icu-sync".equals(username)
               || "system".equals(username)
               || username == null;
    }

    /**
     * 追加数据到已有记录。
     * 保存前重新查询数据库获取最新 desc，避免用户正在编辑时的竞态问题。
     */
    private void appendToExistingRecord(NurseRecords existingRecord, String desc,
                                        Bedside record, String pid) {
        // [修改记录] 2026-08-22 易绍龙: 保存前重新查询，获取用户最新编辑内容
        NurseRecords latest = nurseRecordsRepository.findById(existingRecord.getId()).orElse(existingRecord);
        String oldDesc = latest.getDesc();

        // 防止重复追加
        if (StringUtils.hasText(oldDesc) && oldDesc.contains(desc)) {
            log.info("[TransferScoreSync] 同步内容已存在于记录中，跳过追加 pid={}, nurseRecordId={}",
                    pid, existingRecord.getId());
            return;
        }

        String mergedDesc = StringUtils.hasText(oldDesc)
                ? oldDesc + "\n" + desc
                : desc;
        latest.setDesc(mergedDesc);
        nurseRecordsRepository.save(latest);

        // 创建转运评分 history 记录
        NurseRecordsHistory newHistory = new NurseRecordsHistory();
        newHistory.setPid(pid);
        newHistory.setSyncType(SYNC_TYPE);
        newHistory.setTubeExeId(record.getId());
        newHistory.setTubeType(SYNC_TYPE);
        newHistory.setShiftType("");
        newHistory.setTubeRecordTime(record.getTime());
        newHistory.setNurseRecordId(existingRecord.getId());
        newHistory.setSyncTime(new Date());
        newHistory.setSyncContent(desc);
        nurseRecordsHistoryRepository.insert(newHistory);
    }
}
