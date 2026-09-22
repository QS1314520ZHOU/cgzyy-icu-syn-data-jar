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
 * 统一处理，确保同一时间点的多种数据合并到同一条护理记录中。</p>
 *
 * <p>核心策略：</p>
 * <ul>
 *   <li>先按 (pid, 分钟) 聚齐全部 syncType 的待写内容，再一次决定新建/追加</li>
 *   <li>目标护理记录优先用 history.nurseRecordId 定位（确定性，不依赖时间范围查询）</li>
 *   <li>每个 syncType 通过 nurseRecordsHistory 独立去重；同分钟各类型 history 共享同一 nurseRecordId</li>
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
                    processBatch(batchPids, patientNameMap, syncStartTime,
                            syncedRecords, skippedRecords, updatedRecords, failedRecords);
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
    //  按 (pid, 分钟) 聚齐后一次落库
    // ══════════════════════════════════════════════════════════════

    /** 单个 syncType 在某分钟的待写内容 */
    private static class TypeContent {
        final SyncType syncType;
        final String desc;
        final String bedsideRecordId;
        final Date recordTime;
        final String editUserId;
        final String editUserName;
        final String accountUsername;
        final String accountProfession;

        TypeContent(SyncType syncType, String desc, String bedsideRecordId, Date recordTime,
                    String editUserId, String editUserName, String accountUsername, String accountProfession) {
            this.syncType = syncType;
            this.desc = desc;
            this.bedsideRecordId = bedsideRecordId;
            this.recordTime = recordTime;
            this.editUserId = editUserId;
            this.editUserName = editUserName;
            this.accountUsername = accountUsername;
            this.accountProfession = accountProfession;
        }
    }

    /**
     * 处理一批患者：先聚齐全部 syncType，再按 (pid, 分钟) 合并写入。
     */
    private void processBatch(List<String> batchPids,
                              Map<String, String> patientNameMap,
                              Date syncStartTime,
                              AtomicInteger synced, AtomicInteger skipped,
                              AtomicInteger updated, AtomicInteger failed) {
        // 1. 意识状态编辑人（新建时优先）
        List<Bedside> consciousnessRecords = bedsideRepository.findByPidInAndCodeAndTimeAfter(
                batchPids, "param_Yishi", syncStartTime);
        Map<String, String> consciousnessEditUserMap = new HashMap<>();
        for (Bedside cr : consciousnessRecords) {
            if (StringUtils.hasText(cr.getEditUser())) {
                consciousnessEditUserMap.put(cr.getPid() + "|" + formatMinute(cr.getTime()), cr.getEditUser());
            }
        }

        // 2. 有效 syncType 与 code → type 映射
        List<SyncType> activeTypes = new ArrayList<>();
        Map<String, SyncType> codeToType = new HashMap<>();
        List<String> allCodes = new ArrayList<>();
        for (SyncType st : ALL_SYNC_TYPES) {
            if (st == ST_VENTILATOR && !ventilatorSyncEnabled) {
                continue;
            }
            activeTypes.add(st);
            for (String code : st.codes) {
                codeToType.put(code, st);
                allCodes.add(code);
            }
        }
        if (allCodes.isEmpty()) {
            return;
        }

        // 3. 一次拉取本批全部相关 bedside
        List<Bedside> allRecords = bedsideRepository.findByPidInAndCodeInAndTimeAfterAndValidTrue(
                batchPids, allCodes, syncStartTime);
        if (allRecords.isEmpty()) {
            log.info("[BedsideSync] 本批无 bedside 数据，跳过");
            return;
        }

        // 4. 账户
        Set<String> editUserIds = allRecords.stream()
                .map(Bedside::getEditUser)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        Map<String, Account> accountMap = loadAccounts(editUserIds);

        // 5. 全量 history：类型去重键 + 分钟级 nurseRecordId 索引
        List<NurseRecordsHistory> allHistories = nurseRecordsHistoryRepository.findByPidIn(batchPids);
        Map<String, String> historyTypeKeys = new HashMap<>();
        Map<String, String> nurseRecordIdByMinute = new HashMap<>();
        for (NurseRecordsHistory h : allHistories) {
            if (h.getTubeRecordTime() == null || !StringUtils.hasText(h.getPid())) {
                continue;
            }
            Date minute = TimeUtils.truncateToMinute(h.getTubeRecordTime());
            String minuteKey = h.getPid() + "|" + formatMinute(minute);
            String typeKey = minuteKey + "|" + h.getSyncType();
            historyTypeKeys.put(typeKey, typeKey);
            if (StringUtils.hasText(h.getNurseRecordId())) {
                nurseRecordIdByMinute.putIfAbsent(minuteKey, h.getNurseRecordId());
            }
        }

        // 6. 按 (pid, 分钟) 聚齐全部类型
        Map<String, List<Bedside>> byMinute = new LinkedHashMap<>();
        for (Bedside r : allRecords) {
            if (!StringUtils.hasText(r.getPid()) || !StringUtils.hasText(r.getStrVal())) {
                continue;
            }
            if (r.getTime() == null || !codeToType.containsKey(r.getCode())) {
                continue;
            }
            String key = r.getPid() + "|" + formatMinute(r.getTime());
            byMinute.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        // 7. 每个分钟点只写一次护理记录
        for (Map.Entry<String, List<Bedside>> entry : byMinute.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            if (parts.length < 2) {
                continue;
            }
            String pid = parts[0];
            List<Bedside> minuteRecords = entry.getValue();
            try {
                processMinuteUnit(pid, minuteRecords, codeToType, activeTypes,
                        historyTypeKeys, nurseRecordIdByMinute,
                        patientNameMap.getOrDefault(pid, ""),
                        accountMap, consciousnessEditUserMap,
                        synced, skipped, updated, failed);
            } catch (Exception e) {
                log.error("[BedsideSync] 分钟单元同步异常 pid={}, time={}", pid, minuteRecords.get(0).getTime(), e);
                failed.incrementAndGet();
            }
        }
    }

    /**
     * 处理「同一患者同一分钟」内的全部 syncType：
     * 已有 history 的类型跳过；未同步的类型汇总进同一条护理记录。
     */
    private void processMinuteUnit(String pid,
                                   List<Bedside> minuteRecords,
                                   Map<String, SyncType> codeToType,
                                   List<SyncType> activeTypes,
                                   Map<String, String> historyTypeKeys,
                                   Map<String, String> nurseRecordIdByMinute,
                                   String patientName,
                                   Map<String, Account> accountMap,
                                   Map<String, String> consciousnessEditUserMap,
                                   AtomicInteger synced, AtomicInteger skipped,
                                   AtomicInteger updated, AtomicInteger failed) {
        Date minuteTime = TimeUtils.truncateToMinute(minuteRecords.get(0).getTime());
        String minuteKey = pid + "|" + formatMinute(minuteTime);

        // 按类型分组，并按 ALL_SYNC_TYPES 固定顺序输出，保证 desc 稳定
        Map<String, List<Bedside>> recordsByType = new LinkedHashMap<>();
        for (Bedside r : minuteRecords) {
            SyncType st = codeToType.get(r.getCode());
            if (st != null) {
                recordsByType.computeIfAbsent(st.syncType, k -> new ArrayList<>()).add(r);
            }
        }

        List<TypeContent> toWrite = new ArrayList<>();
        for (SyncType st : activeTypes) {
            List<Bedside> typeRecords = recordsByType.get(st.syncType);
            if (typeRecords == null || typeRecords.isEmpty()) {
                continue;
            }

            if (!passFilter(st, typeRecords, minuteTime) || !passPreCheck(st, typeRecords)) {
                skipped.incrementAndGet();
                continue;
            }

            // 已同步过：不管 bedside 数据是否变化，都不再写入
            if (historyTypeKeys.containsKey(minuteKey + "|" + st.syncType)) {
                skipped.incrementAndGet();
                log.info("[BedsideSync] syncType={} 已同步过，跳过 pid={}, time={}",
                        st.syncType, pid, minuteTime);
                continue;
            }

            String desc = buildDescForType(st, typeRecords);
            if (!StringUtils.hasText(desc)) {
                skipped.incrementAndGet();
                continue;
            }

            Bedside first = typeRecords.get(0);
            String editUserId = first.getEditUser();
            Account editAccount = StringUtils.hasText(editUserId) ? accountMap.get(editUserId) : null;
            toWrite.add(new TypeContent(
                    st, desc, first.getId(), first.getTime(),
                    editUserId,
                    editAccount != null ? editAccount.getTrueName() : "",
                    editAccount != null ? editAccount.getUsername() : "",
                    editAccount != null ? editAccount.getProfession() : ""));
        }

        if (toWrite.isEmpty()) {
            return;
        }

        String combinedDesc = joinDescs(toWrite);

        // 目标记录：1) history.nurseRecordId  2) 同分钟已有记录  3) 新建
        NurseRecords target = resolveTargetRecord(pid, minuteTime, minuteKey, nurseRecordIdByMinute);

        if (target != null) {
            persistToExisting(target, combinedDesc, toWrite, pid, minuteTime);
            updated.incrementAndGet();
            log.info("[BedsideSync] 同分钟合并到已有记录 pid={}, minute={}, types={}, nurseRecordId={}",
                    pid, minuteTime, toWrite.stream().map(t -> t.syncType.syncType).collect(Collectors.toList()),
                    target.getId());
        } else {
            createMergedRecord(pid, patientName, minuteTime, combinedDesc, toWrite,
                    accountMap, consciousnessEditUserMap, minuteKey);
            synced.incrementAndGet();
            log.info("[BedsideSync] 新增合并护理记录 pid={}, minute={}, types={}",
                    pid, minuteTime, toWrite.stream().map(t -> t.syncType.syncType).collect(Collectors.toList()));
        }
    }

    private String joinDescs(List<TypeContent> contents) {
        return contents.stream()
                .map(c -> c.desc)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("；"));
    }

    private NurseRecords resolveTargetRecord(String pid, Date minuteTime, String minuteKey,
                                             Map<String, String> nurseRecordIdByMinute) {
        String existingId = nurseRecordIdByMinute.get(minuteKey);
        if (StringUtils.hasText(existingId)) {
            NurseRecords byHistory = nurseRecordsRepository.findById(existingId).orElse(null);
            if (byHistory != null) {
                return byHistory;
            }
            // 用户已删记录时 history 仍在；不重建该分钟，但新类型仍可挂到时间窗内的其他记录
        }
        return findExistingAutoSynRecord(pid, minuteTime);
    }

    private void persistToExisting(NurseRecords target, String combinedDesc,
                                   List<TypeContent> contents, String pid, Date minuteTime) {
        String oldDesc = target.getDesc();
        if (!StringUtils.hasText(oldDesc) || !oldDesc.contains(combinedDesc)) {
            String merged = StringUtils.hasText(oldDesc)
                    ? oldDesc + "；" + combinedDesc
                    : combinedDesc;
            target.setDesc(merged);
            // 自动同步记录刷新操作人；用户手写记录不覆盖
            if (!isUserWritten(target)) {
                TypeContent first = contents.get(0);
                target.setUsername(first.editUserName);
                target.setUserId(first.editUserId);
                target.setTrueName(first.accountUsername);
                target.setProfessions(first.accountProfession);
            }
            nurseRecordsRepository.save(target);
        }
        for (TypeContent content : contents) {
            insertHistory(pid, content, minuteTime, target.getId());
        }
    }

    private void createMergedRecord(String pid, String patientName, Date minuteTime,
                                    String combinedDesc, List<TypeContent> contents,
                                    Map<String, Account> accountMap,
                                    Map<String, String> consciousnessEditUserMap,
                                    String minuteKey) {
        TypeContent first = contents.get(0);
        String editUserId = first.editUserId;
        String editUserName = first.editUserName;
        String accountUsername = first.accountUsername;
        String accountProfession = first.accountProfession;

        // 新建时优先取意识状态编辑人
        String consciousnessUserId = consciousnessEditUserMap.get(minuteKey);
        Account consciousnessAccount = StringUtils.hasText(consciousnessUserId)
                ? accountMap.get(consciousnessUserId) : null;
        Account editAccount = StringUtils.hasText(editUserId) ? accountMap.get(editUserId) : null;

        if (consciousnessAccount != null && NurseRoleUtils.isNurseRole(consciousnessAccount)) {
            editUserId = consciousnessUserId;
            editUserName = consciousnessAccount.getTrueName();
            accountUsername = consciousnessAccount.getUsername();
            accountProfession = consciousnessAccount.getProfession();
        } else if (editAccount == null || !NurseRoleUtils.isNurseRole(editAccount)) {
            editUserId = null;
            editUserName = "";
            accountUsername = "";
            accountProfession = "";
        }

        NurseRecords newRecord = new NurseRecords();
        newRecord.setPid(pid);
        newRecord.setName(patientName);
        newRecord.setUsername(editUserName);
        newRecord.setUserId(editUserId);
        newRecord.setTrueName(accountUsername);
        newRecord.setProfessions(accountProfession);
        newRecord.setDesc(combinedDesc);
        newRecord.setTime(minuteTime);
        newRecord.setCreateTime(new Date());
        newRecord.setValid(true);
        newRecord.setUseTimes(0);
        newRecord.setDrugExeManualFlag(false);
        newRecord.setAutoSyn(true);
        NurseRecords saved = nurseRecordsRepository.insert(newRecord);

        for (TypeContent content : contents) {
            insertHistory(pid, content, minuteTime, saved.getId());
        }
    }

    private void insertHistory(String pid, TypeContent content, Date minuteTime, String nurseRecordId) {
        NurseRecordsHistory history = new NurseRecordsHistory();
        history.setPid(pid);
        history.setSyncType(content.syncType.syncType);
        history.setTubeExeId(content.bedsideRecordId);
        history.setTubeType(content.syncType.syncType);
        history.setShiftType("");
        history.setTubeRecordTime(minuteTime);
        history.setNurseRecordId(nurseRecordId);
        history.setSyncContent(content.desc);
        history.setSyncTime(new Date());
        nurseRecordsHistoryRepository.insert(history);
    }

    private Map<String, Account> loadAccounts(Set<String> editUserIds) {
        Map<String, Account> accountMap = new HashMap<>();
        if (editUserIds == null || editUserIds.isEmpty()) {
            return accountMap;
        }
        for (Account account : accountRepository.findByIdIn(editUserIds)) {
            accountMap.put(account.getId(), account);
        }
        return accountMap;
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
                return buildDescForAssessmentScore(records);
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
     * 评估评分：值后统一补「分」，如 GCS评分：E3V5M6分；疼痛评分：NRS-0分。
     */
    private String buildDescForAssessmentScore(List<Bedside> records) {
        Map<String, String> codeValMap = new LinkedHashMap<>();
        for (Bedside r : records) {
            String val = r.getStrVal().trim();
            if (!StringUtils.hasText(val)) continue;
            codeValMap.putIfAbsent(r.getCode(), val);
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : ASSESSMENT_CODE_NAME.entrySet()) {
            String val = codeValMap.get(entry.getKey());
            if (!StringUtils.hasText(val)) continue;
            if (sb.length() > 0) sb.append("；");
            sb.append(entry.getValue()).append("：").append(appendScoreUnit(val));
        }
        return sb.toString();
    }

    private String appendScoreUnit(String val) {
        return val.endsWith("分") ? val : val + "分";
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
                String mewsVal = parts[1].trim().replaceAll("(?i)^MEWS\\s*", "");
            return "转运分级标准:" + parts[0].trim() + ",MEWS评分:" + mewsVal + "分";
            }
        }

        if (strVal.contains("级")) {
            return "转运分级标准:" + strVal;
        } else {
            String displayVal = strVal.replaceAll("(?i)^MEWS\\s*", "");
            return "MEWS评分:" + displayVal + "分";
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  共享 helper 方法
    // ══════════════════════════════════════════════════════════════

    private NurseRecords findExistingAutoSynRecord(String pid, Date minuteTime) {
        Date start = TimeUtils.truncateToMinute(minuteTime);
        Date end = new Date(start.getTime() + 60_000);
        List<NurseRecords> records = nurseRecordsRepository.findByPidAndTimeBetween(pid, start, end);
        return records.isEmpty() ? null : records.get(0);
    }

    /** autoSyn=true 即本服务写入，不再因 username 为护士真名而误判为手写 */
    private boolean isUserWritten(NurseRecords record) {
        return !Boolean.TRUE.equals(record.getAutoSyn());
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
