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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 统一 bedside 数据同步服务。
 *
 * <p>将评估评分、皮肤护理、牙齿、降温/升温、转运评分、呼吸机参数六种 bedside 数据
 * 统一处理，确保同一时间点的多种数据合并到同一条护理记录中，避免并发竞态导致的重复记录。</p>
 *
 * <p>核心策略：</p>
 * <ul>
 *   <li>六个 syncType 在同一方法内按固定顺序串行处理</li>
 *   <li>后续 syncType 能发现前面 syncType 已创建的 nurseRecord 并追加内容</li>
 *   <li>每个 syncType 通过 nurseRecordsHistory 独立去重</li>
 *   <li>用户手写记录追加，不覆盖</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BedsideSyncService {

    private final PatientRepository patientRepository;
    private final BedsideRepository bedsideRepository;
    private final NurseRecordsRepository nurseRecordsRepository;
    private final NurseRecordsHistoryRepository nurseRecordsHistoryRepository;
    private final AccountRepository accountRepository;
    private final TubeNursingSyncProperties properties;
    private final MongoTemplate smartCareMongoTemplate;

    /** 呼吸机参数同步开关（默认关闭） */
    @Value("${ventilator-sync.enabled:false}")
    private boolean ventilatorSyncEnabled;

    /** 防重入锁 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 在院状态常量 */
    private static final String STATUS_ADMITTED = "admitted";

    // ══════════════════════════════════════════════════════════════
    //  SyncType 配置
    // ══════════════════════════════════════════════════════════════

    private static class SyncType {
        final String syncType;
        final List<String> codes;
        final boolean requireAllValid;

        SyncType(String syncType, List<String> codes, boolean requireAllValid) {
            this.syncType = syncType;
            this.codes = codes;
            this.requireAllValid = requireAllValid;
        }
    }

    /** 评估评分：5个评分项 */
    private static final SyncType ST_ASSESSMENT = new SyncType("ASSESSMENT_SCORE",
            Arrays.asList("param_score_gcs_obs", "param_镇静唤醒", "param_tengTong_score",
                    "param_score_rass_obs", "param_delirium_score"), false);

    /** 皮肤护理：4个部位 */
    private static final SyncType ST_SKIN_CARE = new SyncType("SKIN_CARE",
            Arrays.asList("param_眼部", "param_口腔", "param_耳部", "param_鼻部"), false);

    /** 牙齿：1个编码 */
    private static final SyncType ST_TOOTH = new SyncType("TOOTH",
            Collections.singletonList("param_yaChi"), false);

    /** 降温/升温：2个编码 */
    private static final SyncType ST_TEMP_MEASURE = new SyncType("TEMP_MEASURE",
            Arrays.asList("param_降温措施", "param_升温措施"), false);

    /** 转运评分：1个编码 */
    private static final SyncType ST_TRANSFER_SCORE = new SyncType("TRANSFER_SCORE",
            Collections.singletonList("param_score_criticalPatientTransferScore"), false);

    /** 呼吸机参数：8个编码 */
    private static final SyncType ST_VENTILATOR = new SyncType("VENTILATOR",
            Arrays.asList("param_HuXiMoShi", "param_FiO2", "param_HuXiPinLv",
                    "param_vent_set_vt", "param_vent_pc", "param_vent_ipap",
                    "param_vent_peep", "param_vent_epap"), false);

    /** 所有 syncType 的处理顺序 */
    private static final List<SyncType> ALL_SYNC_TYPES = Arrays.asList(
            ST_ASSESSMENT, ST_SKIN_CARE, ST_TOOTH,
            ST_TEMP_MEASURE, ST_TRANSFER_SCORE, ST_VENTILATOR);

    // ══════════════════════════════════════════════════════════════
    //  CODE_NAME_MAP（各 syncType 的参数名称映射）
    // ══════════════════════════════════════════════════════════════

    private static final Map<String, String> ASSESSMENT_CODE_NAME = new LinkedHashMap<>();
    static {
        ASSESSMENT_CODE_NAME.put("param_score_gcs_obs", "GCS评分");
        ASSESSMENT_CODE_NAME.put("param_镇静唤醒", "镇静唤醒");
        ASSESSMENT_CODE_NAME.put("param_tengTong_score", "疼痛评分");
        ASSESSMENT_CODE_NAME.put("param_score_rass_obs", "镇静评分");
        ASSESSMENT_CODE_NAME.put("param_delirium_score", "谵妄评分");
    }

    private static final Map<String, String> SKIN_CARE_CODE_NAME = new LinkedHashMap<>();
    static {
        SKIN_CARE_CODE_NAME.put("param_眼部", "眼部");
        SKIN_CARE_CODE_NAME.put("param_口腔", "口腔黏膜");
        SKIN_CARE_CODE_NAME.put("param_耳部", "耳部");
        SKIN_CARE_CODE_NAME.put("param_鼻部", "鼻部");
    }

    private static final Map<String, String> VENTILATOR_CODE_NAME = new LinkedHashMap<>();
    static {
        VENTILATOR_CODE_NAME.put("param_HuXiMoShi", "呼吸机模式");
        VENTILATOR_CODE_NAME.put("param_FiO2", "氧浓度FiO2");
        VENTILATOR_CODE_NAME.put("param_HuXiPinLv", "机控F");
        VENTILATOR_CODE_NAME.put("param_vent_set_vt", "VT");
        VENTILATOR_CODE_NAME.put("param_vent_pc", "PS");
        VENTILATOR_CODE_NAME.put("param_vent_ipap", "IPAP");
        VENTILATOR_CODE_NAME.put("param_vent_peep", "PEEP");
        VENTILATOR_CODE_NAME.put("param_vent_epap", "EPAP");
    }

    /** 降温措施选项映射 */
    private static final Map<String, String> COOLING_OPTIONS = new LinkedHashMap<>();
    static {
        COOLING_OPTIONS.put("①", "①头部冰帽、背部冰毯");
        COOLING_OPTIONS.put("②", "②前额、颈部、腋窝及腹股沟区放置冰袋");
        COOLING_OPTIONS.put("③", "③降低室温");
        COOLING_OPTIONS.put("④", "④血管内降温");
        COOLING_OPTIONS.put("⑤", "⑤冬眠合剂");
    }

    /** 复温措施选项映射 */
    private static final Map<String, String> WARMING_OPTIONS = new LinkedHashMap<>();
    static {
        WARMING_OPTIONS.put("①", "①复温毯、复温帽");
        WARMING_OPTIONS.put("②", "②棉被/毛毯保暖");
        WARMING_OPTIONS.put("③", "③提升室温");
        WARMING_OPTIONS.put("④", "④血管内复温");
        WARMING_OPTIONS.put("⑤", "⑤停用冬眠合剂");
    }

    /** 呼吸机模式编码（VENTILATOR 必须存在） */
    private static final String VENTILATOR_MODE_CODE = "param_HuXiMoShi";

    /** 降温/升温编码 */
    private static final String CODE_COOLING = "param_降温措施";
    private static final String CODE_WARMING = "param_升温措施";

    // ══════════════════════════════════════════════════════════════
    //  同步结果统计
    // ══════════════════════════════════════════════════════════════

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

    // ══════════════════════════════════════════════════════════════
    //  主入口
    // ══════════════════════════════════════════════════════════════

    /**
     * 执行全量同步。
     * <p>查询在院患者，按批次对每个 syncType 串行处理，确保同一时间点的多类型数据合并到同一条护理记录。</p>
     */
    public SyncResult syncAllAdmittedPatients() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[BedsideSync] 上一轮任务尚未完成，跳过本次");
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
            log.info("[BedsideSync] 开始同步 admittedPatients={}", patients.size());

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
            log.info("[BedsideSync] 分批处理: 患者数={}, 批大小={}, 总批数={}",
                    patients.size(), batchSize, totalBatches);

            for (int i = 0; i < pidBatches.size(); i++) {
                List<String> batchPids = pidBatches.get(i);
                int batchNo = i + 1;
                log.info("[BedsideSync] 批次 {}/{} 开始, 患者数={}", batchNo, totalBatches, batchPids.size());

                try {
                    // 批量查询意识状态记录（所有 syncType 共用）
                    List<Bedside> consciousnessRecords = bedsideRepository.findByPidInAndCodeAndTimeAfter(
                            batchPids, "param_Yishi", syncStartTime);
                    Map<String, String> consciousnessEditUserMap = new HashMap<>();
                    for (Bedside cr : consciousnessRecords) {
                        if (StringUtils.hasText(cr.getEditUser())) {
                            String key = cr.getPid() + "|" + formatMinute(cr.getTime());
                            consciousnessEditUserMap.put(key, cr.getEditUser());
                        }
                    }

                    // 对每个 syncType 串行处理
                    for (SyncType st : ALL_SYNC_TYPES) {
                        // 呼吸机同步开关
                        if (st == ST_VENTILATOR && !ventilatorSyncEnabled) {
                            log.debug("[BedsideSync] ventilator-sync.enabled=false，跳过 VENTILATOR");
                            continue;
                        }
                        try {
                            processSyncType(st, batchPids, patientNameMap,
                                    syncStartTime, consciousnessEditUserMap,
                                    syncedRecords, skippedRecords, updatedRecords, failedRecords);
                        } catch (Exception e) {
                            log.error("[BedsideSync] syncType={} 批次 {}/{} 异常",
                                    st.syncType, batchNo, totalBatches, e);
                            failedRecords.incrementAndGet();
                        }
                    }

                    log.info("[BedsideSync] 批次 {}/{} 完成", batchNo, totalBatches);

                } catch (Exception e) {
                    log.error("[BedsideSync] 批次 {}/{} 异常", batchNo, totalBatches, e);
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

            log.info("[BedsideSync] 完成 syncedRecords={} skippedRecords={} updatedRecords={} failedRecords={}",
                    syncedRecords.get(), skippedRecords.get(), updatedRecords.get(), failedRecords.get());

            return new SyncResult(totalPatients.get(), syncedRecords.get(), skippedRecords.get(),
                    updatedRecords.get(), failedRecords.get());

        } catch (Exception e) {
            log.error("[BedsideSync] 同步异常", e);
            return new SyncResult(totalPatients.get(), syncedRecords.get(), skippedRecords.get(),
                    updatedRecords.get(), failedRecords.get());
        } finally {
            running.set(false);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  按 syncType 处理
    // ══════════════════════════════════════════════════════════════

    /**
     * 处理单个 syncType 的同步。
     */
    private void processSyncType(SyncType st,
                                  List<String> batchPids,
                                  Map<String, String> patientNameMap,
                                  Date syncStartTime,
                                  Map<String, String> consciousnessEditUserMap,
                                  AtomicInteger synced, AtomicInteger skipped,
                                  AtomicInteger updated, AtomicInteger failed) {

        // 查询 bedside 数据
        List<Bedside> allRecords = bedsideRepository.findByPidInAndCodeInAndTimeAfterAndValidTrue(
                batchPids, st.codes, syncStartTime);

        if (allRecords.isEmpty()) {
            log.info("[BedsideSync] syncType={} 无数据，跳过", st.syncType);
            return;
        }

        // 批量查询账户信息
        Set<String> editUserIds = allRecords.stream()
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

        // 批量查询历史（按 syncType 独立去重）
        List<NurseRecordsHistory> histories = nurseRecordsHistoryRepository.findByPidInAndSyncType(
                batchPids, st.syncType);
        Map<String, NurseRecordsHistory> historyMap = new HashMap<>();
        for (NurseRecordsHistory history : histories) {
            String key = buildHistoryKey(history.getPid(), st.syncType, history.getTubeRecordTime());
            historyMap.put(key, history);
        }

        // 按 (pid, 分钟时间) 分组
        Map<String, List<Bedside>> groupedRecords = allRecords.stream()
                .filter(r -> StringUtils.hasText(r.getStrVal()))
                .collect(Collectors.groupingBy(
                        r -> r.getPid() + "|" + formatMinute(r.getTime()),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        for (Map.Entry<String, List<Bedside>> entry : groupedRecords.entrySet()) {
            String pid = entry.getKey().split("\\|")[0];
            List<Bedside> records = entry.getValue();
            if (!StringUtils.hasText(pid) || records.isEmpty()) continue;

            String patientName = patientNameMap.getOrDefault(pid, "");

            try {
                Date recordTime = records.get(0).getTime();
                Date minuteTime = TimeUtils.truncateToMinute(recordTime);

                // syncType 特殊过滤
                if (!passFilter(st, records, minuteTime)) {
                    skipped.incrementAndGet();
                    continue;
                }

                // syncType 特殊前置校验（如 VENTILATOR 必须有呼吸机模式）
                if (!passPreCheck(st, records)) {
                    skipped.incrementAndGet();
                    continue;
                }

                String historyKey = buildHistoryKey(pid, st.syncType, recordTime);
                NurseRecordsHistory existingHistory = historyMap.get(historyKey);

                // 已同步过则跳过
                if (existingHistory != null) {
                    skipped.incrementAndGet();
                    log.info("[BedsideSync] syncType={} 已同步过，跳过 pid={}, time={}", st.syncType, pid, minuteTime);
                    continue;
                }

                // 构建 desc
                String desc = buildDescForType(st, records);
                if (!StringUtils.hasText(desc)) {
                    skipped.incrementAndGet();
                    continue;
                }

                // 取第一条记录的编辑人信息
                Bedside firstRecord = records.get(0);
                String editUserId = firstRecord.getEditUser();
                Account editAccount = StringUtils.hasText(editUserId) ? accountMap.get(editUserId) : null;
                String editUserName = editAccount != null ? editAccount.getTrueName() : "";
                String accountUsername = editAccount != null ? editAccount.getUsername() : "";
                String accountProfession = editAccount != null ? editAccount.getProfession() : "";

                // 检查同一时间点是否已有记录（可能是前面 syncType 创建的，也可能是用户手写的）
                NurseRecords existingAtTime = findExistingAutoSynRecord(pid, minuteTime);

                if (existingAtTime != null) {
                    if (isUserWritten(existingAtTime)) {
                        // 用户手写 → 追加
                        appendToExistingRecord(existingAtTime, desc, st.syncType, firstRecord.getId(), recordTime, pid);
                        synced.incrementAndGet();
                        log.info("[BedsideSync] syncType={} 追加到用户记录 pid={}, nurseRecordId={}",
                                st.syncType, pid, existingAtTime.getId());
                    } else {
                        // 自动同步记录 → 拼接
                        mergeToExistingRecord(existingAtTime, desc, st.syncType, firstRecord.getId(), recordTime, pid,
                                editUserId, editUserName, accountUsername, accountProfession);
                        synced.incrementAndGet();
                        log.info("[BedsideSync] syncType={} 拼接到同步记录 pid={}, nurseRecordId={}",
                                st.syncType, pid, existingAtTime.getId());
                    }
                } else {
                    // 无已有记录 → 新建
                    // 优先取意识状态编辑人，没有则 fallback
                    String consciousnessKey = pid + "|" + formatMinute(recordTime);
                    String consciousnessUserId = consciousnessEditUserMap.get(consciousnessKey);
                    Account consciousnessAccount = StringUtils.hasText(consciousnessUserId)
                            ? accountMap.get(consciousnessUserId) : null;

                    if (consciousnessAccount != null && NurseRoleUtils.isNurseRole(consciousnessAccount)) {
                        editUserId = consciousnessUserId;
                        editUserName = consciousnessAccount.getTrueName();
                        accountUsername = consciousnessAccount.getUsername();
                        accountProfession = consciousnessAccount.getProfession();
                    } else if (editAccount != null && NurseRoleUtils.isNurseRole(editAccount)) {
                        // 当前编辑人是护士 → 用当前编辑人（已在上方赋值）
                    } else {
                        editUserId = null;
                        editUserName = "";
                        accountUsername = "";
                        accountProfession = "";
                    }

                    NurseRecords newRecord = createNurseRecord(pid, patientName,
                            editUserName, editUserId, firstRecord, desc,
                            accountUsername, accountProfession);
                    newRecord.setAutoSyn(true);
                    NurseRecords saved = nurseRecordsRepository.insert(newRecord);

                    NurseRecordsHistory newHistory = new NurseRecordsHistory();
                    newHistory.setPid(pid);
                    newHistory.setSyncType(st.syncType);
                    newHistory.setTubeExeId(firstRecord.getId());
                    newHistory.setTubeType(st.syncType);
                    newHistory.setShiftType("");
                    newHistory.setTubeRecordTime(recordTime);
                    newHistory.setNurseRecordId(saved.getId());
                    newHistory.setSyncTime(new Date());
                    newHistory.setSyncContent(desc);
                    nurseRecordsHistoryRepository.insert(newHistory);

                    synced.incrementAndGet();
                    log.info("[BedsideSync] syncType={} 新增同步护理记录 pid={}", st.syncType, pid);
                }
            } catch (Exception e) {
                log.error("[BedsideSync] syncType={} 同步异常 pid={}", st.syncType, pid, e);
                failed.incrementAndGet();
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  syncType 特殊过滤/校验
    // ══════════════════════════════════════════════════════════════

    /**
     * 时间点过滤。TEMP_MEASURE 仅在 0:00、8:00、16:00 同步。
     */
    private boolean passFilter(SyncType st, List<Bedside> records, Date minuteTime) {
        if (st == ST_TEMP_MEASURE) {
            Calendar cal = Calendar.getInstance();
            cal.setTime(minuteTime);
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            int minute = cal.get(Calendar.MINUTE);
            return (hour == 0 && minute == 0) || (hour == 8 && minute == 0) || (hour == 16 && minute == 0);
        }
        return true;
    }

    /**
     * 前置校验。VENTILATOR 要求同一时间点必须存在呼吸机模式数据。
     */
    private boolean passPreCheck(SyncType st, List<Bedside> records) {
        if (st == ST_VENTILATOR) {
            return records.stream()
                    .anyMatch(r -> VENTILATOR_MODE_CODE.equals(r.getCode()) && StringUtils.hasText(r.getStrVal()));
        }
        return true;
    }

    // ══════════════════════════════════════════════════════════════
    //  buildDesc（各 syncType 的描述构建逻辑）
    // ══════════════════════════════════════════════════════════════

    /**
     * 根据 syncType 构建描述文本。
     */
    private String buildDescForType(SyncType st, List<Bedside> records) {
        switch (st.syncType) {
            case "ASSESSMENT_SCORE":
                return buildDescByCodeNameMap(records, ASSESSMENT_CODE_NAME);
            case "SKIN_CARE":
                return buildDescByCodeNameMap(records, SKIN_CARE_CODE_NAME);
            case "TOOTH":
                return buildDescSingleValue(records);
            case "TEMP_MEASURE":
                return buildDescForTemperature(records);
            case "TRANSFER_SCORE":
                return buildDescForTransferScore(records);
            case "VENTILATOR":
                return buildDescByCodeNameMap(records, VENTILATOR_CODE_NAME);
            default:
                return "";
        }
    }

    /**
     * 通用：按 CODE_NAME_MAP 固定顺序构建描述。
     * 格式：{参数名}：{值}；{参数名}：{值}
     */
    private String buildDescByCodeNameMap(List<Bedside> records, Map<String, String> codeNameMap) {
        Map<String, String> codeValMap = new LinkedHashMap<>();
        for (Bedside r : records) {
            String val = r.getStrVal().trim();
            if (!StringUtils.hasText(val)) continue;
            codeValMap.putIfAbsent(r.getCode(), val);
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : codeNameMap.entrySet()) {
            String val = codeValMap.get(entry.getKey());
            if (!StringUtils.hasText(val)) continue;
            if (sb.length() > 0) sb.append("；");
            sb.append(entry.getValue()).append("：").append(val);
        }
        return sb.toString();
    }

    /**
     * 牙齿：单条记录直传。
     */
    private String buildDescSingleValue(List<Bedside> records) {
        if (records.isEmpty()) return "";
        String val = records.get(0).getStrVal().trim();
        return StringUtils.hasText(val) ? val : "";
    }

    /**
     * 降温/升温措施：带选项映射，同一分钟的降温+升温合并。
     */
    private String buildDescForTemperature(List<Bedside> records) {
        StringBuilder sb = new StringBuilder();
        for (Bedside r : records) {
            String val = r.getStrVal().trim();
            if (!StringUtils.hasText(val)) continue;
            if (CODE_COOLING.equals(r.getCode())) {
                if (sb.length() > 0) sb.append("；");
                sb.append("降温措施：").append(resolveOption(COOLING_OPTIONS, val));
            } else if (CODE_WARMING.equals(r.getCode())) {
                if (sb.length() > 0) sb.append("；");
                sb.append("复温措施：").append(resolveOption(WARMING_OPTIONS, val));
            }
        }
        return sb.toString();
    }

    /**
     * 转运评分：数据格式转换。
     */
    private String buildDescForTransferScore(List<Bedside> records) {
        if (records.isEmpty()) return "";
        String strVal = records.get(0).getStrVal().trim();
        return convertTransferScoreData(strVal);
    }

    // ══════════════════════════════════════════════════════════════
    //  数据转换辅助
    // ══════════════════════════════════════════════════════════════

    /**
     * 根据选项映射解析 strVal，支持多选用、分隔。
     */
    private String resolveOption(Map<String, String> optionMap, String strVal) {
        if (!strVal.contains("、")) {
            String mapped = optionMap.get(strVal);
            return mapped != null ? mapped : strVal;
        }
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
     * 转换转运评分数据格式。
     */
    private String convertTransferScoreData(String strVal) {
        if (!StringUtils.hasText(strVal)) return "";
        strVal = strVal.trim();

        if (strVal.contains("-")) {
            String[] parts = strVal.split("-", 2);
            if (parts.length >= 2) {
                return "转运分级标准:" + parts[0].trim() + ",MEWS评分:" + parts[1].trim() + "分";
            }
        }

        if (strVal.contains("级")) {
            return "转运分级标准:" + strVal;
        } else {
            return "MEWS评分:" + strVal + "分";
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  合并/追加/新建 nurseRecord
    // ══════════════════════════════════════════════════════════════

    /**
     * 合并内容到已有的自动同步记录。
     */
    private void mergeToExistingRecord(NurseRecords existingRecord, String desc,
                                        String syncType, String bedsideRecordId,
                                        Date recordTime, String pid,
                                        String editUserId, String editUserName,
                                        String accountUsername, String accountProfession) {
        String oldDesc = existingRecord.getDesc();
        String mergedDesc = StringUtils.hasText(oldDesc)
                ? oldDesc + "；" + desc
                : desc;
        existingRecord.setDesc(mergedDesc);
        existingRecord.setUsername(editUserName);
        existingRecord.setUserId(editUserId);
        existingRecord.setTrueName(accountUsername);
        existingRecord.setProfessions(accountProfession);
        nurseRecordsRepository.save(existingRecord);

        // 更新或创建该 syncType 对应的 history 记录
        NurseRecordsHistory existingHistory = findHistoryBySyncTypeAndNurseRecordId(syncType, existingRecord.getId());
        if (existingHistory != null) {
            existingHistory.setSyncContent(desc);
            existingHistory.setSyncTime(new Date());
            nurseRecordsHistoryRepository.save(existingHistory);
        } else {
            NurseRecordsHistory newHistory = new NurseRecordsHistory();
            newHistory.setPid(pid);
            newHistory.setSyncType(syncType);
            newHistory.setTubeExeId(bedsideRecordId);
            newHistory.setTubeType(syncType);
            newHistory.setShiftType("");
            newHistory.setTubeRecordTime(recordTime);
            newHistory.setNurseRecordId(existingRecord.getId());
            newHistory.setSyncContent(desc);
            newHistory.setSyncTime(new Date());
            nurseRecordsHistoryRepository.insert(newHistory);
        }
    }

    /**
     * 追加内容到用户手写记录。
     * 保存前重新查询数据库获取最新 desc，避免用户正在编辑时的竞态问题。
     */
    private void appendToExistingRecord(NurseRecords existingRecord, String desc,
                                         String syncType, String bedsideRecordId,
                                         Date recordTime, String pid) {
        // 防止重复追加：检查该 syncType 是否已对该 nurseRecord 创建过 history
        NurseRecordsHistory existingHistory = findHistoryBySyncTypeAndNurseRecordId(syncType, existingRecord.getId());
        if (existingHistory != null) {
            // 更新已有 history 的内容
            NurseRecords latest = nurseRecordsRepository.findById(existingRecord.getId()).orElse(existingRecord);
            String oldDesc = latest.getDesc();
            if (StringUtils.hasText(oldDesc) && oldDesc.contains(desc)) {
                log.info("[BedsideSync] syncType={} 同步内容已存在，跳过追加 pid={}, nurseRecordId={}",
                        syncType, pid, existingRecord.getId());
                return;
            }
            // 内容有变化，更新 desc 和 history
            String mergedDesc = StringUtils.hasText(oldDesc)
                    ? oldDesc + "；" + desc
                    : desc;
            latest.setDesc(mergedDesc);
            nurseRecordsRepository.save(latest);
            existingHistory.setSyncContent(desc);
            existingHistory.setSyncTime(new Date());
            nurseRecordsHistoryRepository.save(existingHistory);
        } else {
            // 首次同步，追加内容并创建 history
            NurseRecords latest = nurseRecordsRepository.findById(existingRecord.getId()).orElse(existingRecord);
            String oldDesc = latest.getDesc();

            if (StringUtils.hasText(oldDesc) && oldDesc.contains(desc)) {
                log.info("[BedsideSync] syncType={} 同步内容已存在，跳过追加 pid={}, nurseRecordId={}",
                        syncType, pid, existingRecord.getId());
                return;
            }

            String mergedDesc = StringUtils.hasText(oldDesc)
                    ? oldDesc + "；" + desc
                    : desc;
            latest.setDesc(mergedDesc);
            nurseRecordsRepository.save(latest);

            NurseRecordsHistory newHistory = new NurseRecordsHistory();
            newHistory.setPid(pid);
            newHistory.setSyncType(syncType);
            newHistory.setTubeExeId(bedsideRecordId);
            newHistory.setTubeType(syncType);
            newHistory.setShiftType("");
            newHistory.setTubeRecordTime(recordTime);
            newHistory.setNurseRecordId(existingRecord.getId());
            newHistory.setSyncContent(desc);
            newHistory.setSyncTime(new Date());
            nurseRecordsHistoryRepository.insert(newHistory);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  共享 helper 方法
    // ══════════════════════════════════════════════════════════════

    private String buildHistoryKey(String pid, String syncType, Date recordTime) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmm");
        return pid + "_" + syncType + "_" + sdf.format(recordTime);
    }

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

    private NurseRecords findExistingAutoSynRecord(String pid, Date minuteTime) {
        Date start = TimeUtils.truncateToMinute(minuteTime);
        Date end = new Date(start.getTime() + 60_000);
        List<NurseRecords> records = nurseRecordsRepository.findByPidAndTimeBetween(pid, start, end);
        return records.isEmpty() ? null : records.get(0);
    }

    /**
     * 根据 syncType 和 nurseRecordId 查找已有的 history 记录。
     * 用于判断该 syncType 是否已对该护理记录同步过，避免重复创建 history。
     */
    private NurseRecordsHistory findHistoryBySyncTypeAndNurseRecordId(String syncType, String nurseRecordId) {
        Query query = new Query(
                Criteria.where("syncType").is(syncType)
                        .and("nurseRecordId").is(nurseRecordId));
        return smartCareMongoTemplate.findOne(query, NurseRecordsHistory.class);
    }

    private boolean isUserWritten(NurseRecords record) {
        return !Boolean.TRUE.equals(record.getAutoSyn())
               || !isSystemAccount(record.getUsername());
    }

    private boolean isSystemAccount(String username) {
        return "系统同步".equals(username)
               || "icu-sync".equals(username)
               || "system".equals(username)
               || username == null;
    }

    private String formatMinute(Date time) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmm");
        return sdf.format(TimeUtils.truncateToMinute(time));
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            result.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return result;
    }
}
