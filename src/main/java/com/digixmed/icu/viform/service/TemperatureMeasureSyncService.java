package com.digixmed.icu.viform.service;

import com.digixmed.icu.viform.common.TimeUtils;
import com.digixmed.icu.viform.config.TubeNursingSyncProperties;
import com.digixmed.icu.viform.entity.Bedside;
import com.digixmed.icu.viform.entity.NurseRecords;
import com.digixmed.icu.viform.entity.NurseRecordsHistory;
import com.digixmed.icu.viform.entity.Patient;
import com.digixmed.icu.viform.entity.Account;
import com.digixmed.icu.viform.repository.smartcare.AccountRepository;
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
    private final AccountRepository accountRepository;
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

    /** 降温措施选项映射：strVal → 显示文本 */
    private static final Map<String, String> COOLING_OPTIONS = new LinkedHashMap<>();
    /** 复温措施选项映射：strVal → 显示文本 */
    private static final Map<String, String> WARMING_OPTIONS = new LinkedHashMap<>();

    static {
        COOLING_OPTIONS.put("①", "①头部冰帽、背部冰毯");
        COOLING_OPTIONS.put("②", "②前额、颈部、腋窝及腹股沟区放置冰袋");
        COOLING_OPTIONS.put("③", "③降低室温");
        COOLING_OPTIONS.put("④", "④血管内降温");
        COOLING_OPTIONS.put("⑤", "⑤冬眠合剂");

        WARMING_OPTIONS.put("①", "①复温毯、复温帽");
        WARMING_OPTIONS.put("②", "②棉被/毛毯保暖");
        WARMING_OPTIONS.put("③", "③提升室温");
        WARMING_OPTIONS.put("④", "④血管内复温");
        WARMING_OPTIONS.put("⑤", "⑤停用冬眠合剂");
    }

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

                    // 批量查询账户信息
                    Set<String> editUserIds = tempRecords.stream()
                            .map(Bedside::getEditUser)
                            .filter(StringUtils::hasText)
                            .collect(Collectors.toSet());
                    Map<String, Account> accountMap = new HashMap<>();
                    if (!editUserIds.isEmpty()) {
                        List<Account> accounts = accountRepository.findByIdIn(editUserIds);
                        for (Account account : accounts) {
                            accountMap.put(account.getId(), account);
                        }
                        log.info("[TempMeasureSync] 批次 {}/{} account 命中: {}/{}",
                                batchNo, totalBatches, accountMap.size(), editUserIds.size());
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
                                    historyMap, accountMap, syncedRecords, skippedRecords,
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
     * <p>按分钟分组，同一分钟的降温+升温合并为一条 desc。
     * 只在每天8:00、16:00、0:00三个时间点进行同步，其他时间点的数据不管。</p>
     */
    private void processPatientRecords(String pid, String patientName,
                                        List<Bedside> records,
                                        Map<String, NurseRecordsHistory> historyMap,
                                        Map<String, Account> accountMap,
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
        Calendar calendar = Calendar.getInstance();

        for (Map.Entry<String, List<Bedside>> entry : byMinute.entrySet()) {
            String minuteKey = entry.getKey();
            List<Bedside> minuteRecords = entry.getValue();

            // 只在每天8:00、16:00、0:00三个时间点进行同步
            Bedside firstRecord = minuteRecords.get(0);
            Date minuteTime = firstRecord.getTime();
            calendar.setTime(minuteTime);
            int hour = calendar.get(Calendar.HOUR_OF_DAY);
            int minute = calendar.get(Calendar.MINUTE);
            if (!isTargetTimePoint(hour, minute)) {
                log.debug("[TempMeasureSync] 非目标时间点，跳过 pid={}, time={}, hour={}, minute={}",
                        pid, minuteTime, hour, minute);
                skipped.incrementAndGet();
                continue;
            }

            try {
                // 构建 desc
                String desc = buildDesc(minuteRecords);
                if (!StringUtils.hasText(desc)) {
                    skipped.incrementAndGet();
                    continue;
                }

                // 取第一条记录的操作人信息（firstRecord和minuteTime已在前面定义）
                String editUserId = firstRecord.getEditUser();
                Account editAccount = StringUtils.hasText(editUserId) ? accountMap.get(editUserId) : null;
                String trueName = editAccount != null ? editAccount.getTrueName() : "";
                String accountUsername = editAccount != null ? editAccount.getUsername() : "";
                String accountProfession = editAccount != null ? editAccount.getProfession() : "";
                String historyKey = pid + "_" + SYNC_TYPE + "_" + minuteKey;

                NurseRecordsHistory existingHistory = historyMap.get(historyKey);

                // 核心逻辑：日志表有记录 = 已同步过 = 直接跳过
                if (existingHistory != null) {
                    skipped.incrementAndGet();
                    log.info("[TempMeasureSync] 已同步过，跳过 pid={}, time={}", pid, minuteTime);
                    continue;
                }

                {
                    // 无历史 → 检查同时间点是否已有记录（可能是用户手写的，也可能是其他同步的）
                    NurseRecords existingAtTime = findExistingAutoSynRecord(pid, minuteTime);

                    if (existingAtTime != null) {
                        // 检查是否是用户手写的
                        if (isUserWritten(existingAtTime)) {
                            // [修改记录] 2026-08-22 易绍龙: 用户手写记录 → 始终追加，不跳过
                            appendToExistingRecord(existingAtTime, desc, minuteTime, pid, editUserId, trueName);
                            synced.incrementAndGet();
                            log.info("[TempMeasureSync] 追加体温数据到用户记录 pid={}, nurseRecordId={}", pid, existingAtTime.getId());
                        } else {
                            // 其他同步记录 → 体温数据拼接到已有记录后面
                            String oldDesc = existingAtTime.getDesc();
                            String mergedDesc = StringUtils.hasText(oldDesc)
                                    ? oldDesc + "\n" + desc
                                    : desc;
                            existingAtTime.setDesc(mergedDesc);
                            existingAtTime.setUsername(trueName);
                            existingAtTime.setUserId(editUserId);
                            nurseRecordsRepository.save(existingAtTime);

                            // 创建体温 history 记录
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
                            log.info("[TempMeasureSync] 体温数据拼接到同步记录 pid={}, nurseRecordId={}", pid, existingAtTime.getId());
                        }
                    } else {
                        // 无已有记录 → 新建（标记为自动同步）
                        NurseRecords newRecord = createNurseRecord(pid, patientName, desc, minuteTime, editUserId, trueName,
                                accountUsername, accountProfession);
                        newRecord.setAutoSyn(true);  // 标记为自动同步
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
                        log.info("[TempMeasureSync] 新增同步护理记录 pid={}, time={}", pid, minuteTime);
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
                sb.append("降温措施：").append(resolveOption(COOLING_OPTIONS, val));
            } else if (CODE_WARMING.equals(r.getCode())) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("复温措施：").append(resolveOption(WARMING_OPTIONS, val));
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 根据选项映射解析 strVal，支持多选用、分隔。
     * 如 "①、②" → "①头部冰帽、背部冰毯、②前额、颈部、腋窝及腹股沟区放置冰袋"
     */
    private String resolveOption(Map<String, String> optionMap, String strVal) {
        if (!strVal.contains("、")) {
            // 单选
            String mapped = optionMap.get(strVal);
            return mapped != null ? mapped : strVal;
        }
        // 多选：按、分隔，逐个映射
        String[] parts = strVal.split("、");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!StringUtils.hasText(trimmed)) continue;
            if (result.length() > 0) result.append("、");
            String mapped = optionMap.get(trimmed);
            result.append(mapped != null ? mapped : trimmed);
        }
        return result.toString();
    }

    /**
     * 查找指定患者在同一时间点已有的护理记录。
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
     * 追加数据到已有记录（不覆盖用户操作人信息）。
     * 保存前重新查询数据库获取最新 desc，避免用户正在编辑时的竞态问题。
     */
    private void appendToExistingRecord(NurseRecords existingRecord, String desc,
                                        Date minuteTime, String pid,
                                        String editUserId, String trueName) {
        // [修改记录] 2026-08-22 易绍龙: 保存前重新查询，获取用户最新编辑内容
        NurseRecords latest = nurseRecordsRepository.findById(existingRecord.getId()).orElse(existingRecord);
        String oldDesc = latest.getDesc();

        // 防止重复追加
        if (StringUtils.hasText(oldDesc) && oldDesc.contains(desc)) {
            log.info("[TempMeasureSync] 同步内容已存在于记录中，跳过追加 pid={}, nurseRecordId={}",
                    pid, existingRecord.getId());
            return;
        }

        String mergedDesc = StringUtils.hasText(oldDesc)
                ? oldDesc + "\n" + desc
                : desc;
        latest.setDesc(mergedDesc);
        // [修改记录] 2026-08-22 易绍龙: 追加时不覆盖用户的 username/userId
        nurseRecordsRepository.save(latest);

        // 创建体温 history 记录
        NurseRecordsHistory newHistory = new NurseRecordsHistory();
        newHistory.setPid(pid);
        newHistory.setSyncType(SYNC_TYPE);
        newHistory.setTubeRecordTime(minuteTime);
        newHistory.setNurseRecordId(existingRecord.getId());
        newHistory.setSyncContent(desc);
        newHistory.setSyncTime(new Date());
        nurseRecordsHistoryRepository.insert(newHistory);
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
                                            String desc, Date time,
                                            String editUserId, String trueName,
                                            String accountUsername, String accountProfession) {
        NurseRecords nurseRecord = new NurseRecords();
        nurseRecord.setPid(pid);
        nurseRecord.setName(patientName);
        nurseRecord.setDesc(desc);
        nurseRecord.setTime(TimeUtils.truncateToMinute(time));
        nurseRecord.setCreateTime(new Date());
        nurseRecord.setUsername(trueName);
        nurseRecord.setUserId(editUserId);
        nurseRecord.setTrueName(accountUsername);
        nurseRecord.setProfessions(accountProfession);
        nurseRecord.setValid(true);
        nurseRecord.setUseTimes(0);
        nurseRecord.setDrugExeManualFlag(false);
        nurseRecord.setAutoSyn(false);
        return nurseRecord;
    }

    /**
     * 判断是否是目标时间点（8:00、16:00、0:00）。
     *
     * @param hour   小时（0-23）
     * @param minute 分钟（0-59）
     * @return true=是目标时间点，false=不是
     */
    private boolean isTargetTimePoint(int hour, int minute) {
        // 目标时间点：0:00、8:00、16:00
        return (hour == 0 && minute == 0)
                || (hour == 8 && minute == 0)
                || (hour == 16 && minute == 0);
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
