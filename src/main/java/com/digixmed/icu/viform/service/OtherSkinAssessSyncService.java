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
import org.bson.Document;
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
 * 其他皮肤情况评估同步服务（skinCareInfo.otherSkinAssessList → 护理记录）。
 *
 * <p>核心策略：</p>
 * <ul>
 *   <li>取 skinCareInfo 中 type=其他皮肤情况评估 的文档，遍历 otherSkinAssessList 明细；
 *       status=invalid 的评估单/明细为无效数据，直接过滤</li>
 *   <li>按明细时间（recordTime）写入 nurseRecordsHistory 去重，每条明细只同步一次，
 *       不管源数据后续是否变化都不再写入</li>
 *   <li>同步前清理 skinMessage 多余标点（如「无红肿、；」→「无红肿；」）</li>
 *   <li>记录者与 BedsideSyncService 一致：优先取意识状态（param_Yishi）编辑人；
 *       意识没有时取明细 createdUser（如「工程师」）</li>
 *   <li>同一分钟的多条明细合并进同一条护理记录，目标定位策略与 BedsideSyncService 相同</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtherSkinAssessSyncService {

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

    /** 同步类型标识 */
    private static final String SYNC_TYPE_OTHER_SKIN = "OTHER_SKIN";

    /** 源集合与筛选条件 */
    private static final String COLLECTION_SKIN_CARE_INFO = "skinCareInfo";
    private static final String TYPE_OTHER_SKIN = "其他皮肤情况评估";
    private static final String CODE_CONSCIOUSNESS = "param_Yishi";

    /** 明细列表字段 */
    private static final String LIST_OTHER_SKIN = "otherSkinAssessList";

    // ══════════════════════════════════════════════════════════════
    //  同步结果统计
    // ══════════════════════════════════════════════════════════════

    public static class SyncResult {
        public final int totalPatients;
        public final int totalItems;
        public final int syncedRecords;
        public final int skippedRecords;
        public final int updatedRecords;
        public final int failedRecords;

        public SyncResult(int totalPatients, int totalItems, int syncedRecords,
                          int skippedRecords, int updatedRecords, int failedRecords) {
            this.totalPatients = totalPatients;
            this.totalItems = totalItems;
            this.syncedRecords = syncedRecords;
            this.skippedRecords = skippedRecords;
            this.updatedRecords = updatedRecords;
            this.failedRecords = failedRecords;
        }
    }

    /** otherSkinAssessList 明细项（已清洗） */
    private static class SkinItem {
        final String pid;
        final String itemId;
        final Date recordTime;
        final String desc;
        final String createdUser;
        final String createdAccountId;

        SkinItem(String pid, String itemId, Date recordTime, String desc,
                 String createdUser, String createdAccountId) {
            this.pid = pid;
            this.itemId = itemId;
            this.recordTime = recordTime;
            this.desc = desc;
            this.createdUser = createdUser;
            this.createdAccountId = createdAccountId;
        }
    }

    /** 生成护理记录时解析出的记录者 */
    private static class Recorder {
        final String userId;
        final String username;
        final String trueName;
        final String professions;

        Recorder(String userId, String username, String trueName, String professions) {
            this.userId = userId;
            this.username = username;
            this.trueName = trueName;
            this.professions = professions;
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  主入口
    // ══════════════════════════════════════════════════════════════

    /**
     * 执行全量同步。查询在院患者的其他皮肤情况评估明细，按 (pid, 分钟) 合并写入护理记录。
     */
    public SyncResult syncAllAdmittedPatients() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[OtherSkinSync] 上一轮任务尚未完成，跳过本次");
            return new SyncResult(0, 0, 0, 0, 0, 0);
        }

        AtomicInteger totalPatients = new AtomicInteger();
        AtomicInteger totalItems = new AtomicInteger();
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
            log.info("[OtherSkinSync] 开始同步 admittedPatients={}", patients.size());

            if (patients.isEmpty()) {
                return new SyncResult(0, 0, 0, 0, 0, 0);
            }

            Map<String, String> patientNameMap = new HashMap<>();
            for (Patient p : patients) {
                patientNameMap.put(p.getId(), p.getName());
            }

            // 2. 按批次处理患者
            int batchSize = properties.getBatchSize();
            List<List<String>> pidBatches = partition(
                    patients.stream().map(Patient::getId).collect(Collectors.toList()), batchSize);
            int totalBatches = pidBatches.size();
            log.info("[OtherSkinSync] 分批处理: 患者数={}, 批大小={}, 总批数={}",
                    patients.size(), batchSize, totalBatches);

            for (int i = 0; i < pidBatches.size(); i++) {
                List<String> batchPids = pidBatches.get(i);
                int batchNo = i + 1;
                log.info("[OtherSkinSync] 批次 {}/{} 开始, 患者数={}", batchNo, totalBatches, batchPids.size());

                try {
                    processBatch(batchPids, patientNameMap,
                            totalItems, syncedRecords, skippedRecords, updatedRecords, failedRecords);
                    log.info("[OtherSkinSync] 批次 {}/{} 完成", batchNo, totalBatches);
                } catch (Exception e) {
                    log.error("[OtherSkinSync] 批次 {}/{} 异常", batchNo, totalBatches, e);
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

            log.info("[OtherSkinSync] 完成 totalItems={} syncedRecords={} skippedRecords={} updatedRecords={} failedRecords={}",
                    totalItems.get(), syncedRecords.get(), skippedRecords.get(),
                    updatedRecords.get(), failedRecords.get());

            return new SyncResult(totalPatients.get(), totalItems.get(), syncedRecords.get(),
                    skippedRecords.get(), updatedRecords.get(), failedRecords.get());

        } catch (Exception e) {
            log.error("[OtherSkinSync] 同步异常", e);
            return new SyncResult(totalPatients.get(), totalItems.get(), syncedRecords.get(),
                    skippedRecords.get(), updatedRecords.get(), failedRecords.get());
        } finally {
            running.set(false);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  按批次处理
    // ══════════════════════════════════════════════════════════════

    private void processBatch(List<String> batchPids,
                              Map<String, String> patientNameMap,
                              AtomicInteger totalItems,
                              AtomicInteger synced, AtomicInteger skipped,
                              AtomicInteger updated, AtomicInteger failed) {
        // 1. skinCareInfo：type=其他皮肤情况评估
        Query query = new Query();
        query.addCriteria(Criteria.where("pid").in(batchPids).and("type").is(TYPE_OTHER_SKIN));
        List<Document> docs = smartCareMongoTemplate.find(query, Document.class, COLLECTION_SKIN_CARE_INFO);
        if (docs.isEmpty()) {
            log.info("[OtherSkinSync] 本批无 skinCareInfo 数据，跳过");
            return;
        }

        // 2. 全量 history：明细时间去重键 + 分钟级 nurseRecordId 索引
        List<NurseRecordsHistory> allHistories = nurseRecordsHistoryRepository.findByPidIn(batchPids);
        Set<String> syncedTimeKeys = new HashSet<>();
        Map<String, String> nurseRecordIdByMinute = new HashMap<>();
        for (NurseRecordsHistory h : allHistories) {
            if (h.getTubeRecordTime() == null || !StringUtils.hasText(h.getPid())) {
                continue;
            }
            if (SYNC_TYPE_OTHER_SKIN.equals(h.getSyncType())) {
                // 按明细原始时间精确去重：只同步一次
                syncedTimeKeys.add(h.getPid() + "|" + h.getTubeRecordTime().getTime());
            }
            String minuteKey = h.getPid() + "|" + formatMinute(h.getTubeRecordTime());
            if (StringUtils.hasText(h.getNurseRecordId())) {
                nurseRecordIdByMinute.putIfAbsent(minuteKey, h.getNurseRecordId());
            }
        }

        // 3. 提取明细：仅保留有效且未同步过的
        List<SkinItem> pendingItems = new ArrayList<>();
        for (Document doc : docs) {
            String pid = doc.getString("pid");
            if (!StringUtils.hasText(pid)) {
                continue;
            }
            // status=invalid 表示评估单已作废，整单过滤不同步
            if (isInvalidStatus(doc)) {
                log.info("[OtherSkinSync] 评估单 status=invalid 无效数据，过滤 pid={}, docId={}",
                        pid, doc.get("_id"));
                continue;
            }
            for (Document item : getList(doc, LIST_OTHER_SKIN)) {
                totalItems.incrementAndGet();

                // status=invalid 的明细同样过滤
                if (isInvalidStatus(item)) {
                    skipped.incrementAndGet();
                    continue;
                }

                Boolean valid = item.getBoolean("valid");
                if (Boolean.FALSE.equals(valid)) {
                    skipped.incrementAndGet();
                    continue;
                }

                Date itemTime = getItemTime(item);
                if (itemTime == null) {
                    skipped.incrementAndGet();
                    continue;
                }

                // 清理多余标点：如「无红肿、；」→「无红肿；」
                String desc = cleanPunctuation(item.getString("skinMessage"));
                if (!StringUtils.hasText(desc)) {
                    skipped.incrementAndGet();
                    continue;
                }

                // 已同步过：不管数据是否变化，都不再写入
                if (syncedTimeKeys.contains(pid + "|" + itemTime.getTime())) {
                    skipped.incrementAndGet();
                    log.debug("[OtherSkinSync] 明细已同步过，跳过 pid={}, time={}", pid, itemTime);
                    continue;
                }

                String itemId = item.get("_id") != null ? String.valueOf(item.get("_id")) : "";
                pendingItems.add(new SkinItem(pid, itemId, itemTime, desc,
                        item.getString("createdUser"), item.getString("createdAccountId")));
            }
        }

        if (pendingItems.isEmpty()) {
            log.info("[OtherSkinSync] 本批无待同步明细，跳过");
            return;
        }

        // 4. 意识状态编辑人（新建时优先）：只查待同步明细时间之后的
        Date minItemTime = pendingItems.stream()
                .map(i -> i.recordTime)
                .min(Date::compareTo)
                .orElse(new Date());
        List<Bedside> consciousnessRecords = bedsideRepository.findByPidInAndCodeAndTimeAfter(
                batchPids, CODE_CONSCIOUSNESS, minItemTime);
        Map<String, String> consciousnessEditUserMap = new HashMap<>();
        for (Bedside cr : consciousnessRecords) {
            if (StringUtils.hasText(cr.getEditUser()) && cr.getTime() != null) {
                consciousnessEditUserMap.put(cr.getPid() + "|" + formatMinute(cr.getTime()), cr.getEditUser());
            }
        }

        // 5. 账户：意识编辑人 + 明细创建人
        Set<String> userIds = pendingItems.stream()
                .map(i -> i.createdAccountId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        userIds.addAll(consciousnessEditUserMap.values());
        Map<String, Account> accountMap = loadAccounts(userIds);

        // 6. 按 (pid, 分钟) 聚齐全部明细
        Map<String, List<SkinItem>> byMinute = new LinkedHashMap<>();
        for (SkinItem item : pendingItems) {
            String key = item.pid + "|" + formatMinute(item.recordTime);
            byMinute.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }

        // 7. 每个分钟点只写一次护理记录
        for (Map.Entry<String, List<SkinItem>> entry : byMinute.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            if (parts.length < 2) {
                continue;
            }
            String pid = parts[0];
            List<SkinItem> minuteItems = entry.getValue();
            // 按明细时间排序，保证 desc 稳定
            minuteItems.sort(Comparator.comparing(i -> i.recordTime));
            try {
                processMinuteUnit(pid, minuteItems,
                        nurseRecordIdByMinute,
                        patientNameMap.getOrDefault(pid, ""),
                        accountMap, consciousnessEditUserMap,
                        synced, updated);
            } catch (Exception e) {
                log.error("[OtherSkinSync] 分钟单元同步异常 pid={}, time={}",
                        pid, minuteItems.get(0).recordTime, e);
                failed.incrementAndGet();
            }
        }
    }

    /**
     * 处理「同一患者同一分钟」内的全部明细：合并进同一条护理记录。
     */
    private void processMinuteUnit(String pid,
                                   List<SkinItem> items,
                                   Map<String, String> nurseRecordIdByMinute,
                                   String patientName,
                                   Map<String, Account> accountMap,
                                   Map<String, String> consciousnessEditUserMap,
                                   AtomicInteger synced, AtomicInteger updated) {
        Date minuteTime = TimeUtils.truncateToMinute(items.get(0).recordTime);
        String minuteKey = pid + "|" + formatMinute(minuteTime);
        String combinedDesc = items.stream()
                .map(i -> i.desc)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("；"));

        Recorder recorder = resolveRecorder(items, minuteKey, accountMap, consciousnessEditUserMap);

        // 目标记录：1) history.nurseRecordId  2) 同分钟已有记录  3) 新建
        NurseRecords target = resolveTargetRecord(pid, minuteTime, minuteKey, nurseRecordIdByMinute);

        if (target != null) {
            persistToExisting(target, combinedDesc, recorder, pid, items);
            updated.incrementAndGet();
            log.info("[OtherSkinSync] 同分钟合并到已有记录 pid={}, minute={}, items={}, nurseRecordId={}",
                    pid, minuteTime, items.size(), target.getId());
        } else {
            createMergedRecord(pid, patientName, minuteTime, combinedDesc, recorder, items);
            synced.incrementAndGet();
            log.info("[OtherSkinSync] 新增合并护理记录 pid={}, minute={}, items={}",
                    pid, minuteTime, items.size());
        }
    }

    /**
     * 记录者：优先意识状态编辑人（须为护士角色），意识没有时取明细 createdUser。
     */
    private Recorder resolveRecorder(List<SkinItem> items, String minuteKey,
                                     Map<String, Account> accountMap,
                                     Map<String, String> consciousnessEditUserMap) {
        // 与 BedsideSyncService 一致：新建时优先取意识状态编辑人
        String consciousnessUserId = consciousnessEditUserMap.get(minuteKey);
        Account consciousnessAccount = StringUtils.hasText(consciousnessUserId)
                ? accountMap.get(consciousnessUserId) : null;
        if (consciousnessAccount != null && NurseRoleUtils.isNurseRole(consciousnessAccount)) {
            return new Recorder(consciousnessUserId, consciousnessAccount.getTrueName(),
                    consciousnessAccount.getUsername(), consciousnessAccount.getProfession());
        }

        // 意识没有：取 otherSkinAssessList 明细的 createdUser
        SkinItem first = items.stream()
                .filter(i -> StringUtils.hasText(i.createdUser))
                .findFirst()
                .orElse(items.get(0));
        Account createdAccount = StringUtils.hasText(first.createdAccountId)
                ? accountMap.get(first.createdAccountId) : null;
        String createdUser = StringUtils.hasText(first.createdUser)
                ? first.createdUser
                : (createdAccount != null ? createdAccount.getTrueName() : "");
        return new Recorder(first.createdAccountId, createdUser,
                createdAccount != null ? createdAccount.getUsername() : createdUser,
                createdAccount != null ? createdAccount.getProfession() : "");
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

    private void persistToExisting(NurseRecords target, String combinedDesc, Recorder recorder,
                                   String pid, List<SkinItem> items) {
        String oldDesc = target.getDesc();
        if (!StringUtils.hasText(oldDesc) || !oldDesc.contains(combinedDesc)) {
            String merged = StringUtils.hasText(oldDesc)
                    ? oldDesc + "；" + combinedDesc
                    : combinedDesc;
            target.setDesc(merged);
            // 自动同步记录刷新操作人；用户手写记录不覆盖
            if (!isUserWritten(target)) {
                target.setUsername(recorder.username);
                target.setUserId(recorder.userId);
                target.setTrueName(recorder.trueName);
                target.setProfessions(recorder.professions);
            }
            nurseRecordsRepository.save(target);
        }
        for (SkinItem item : items) {
            insertHistory(pid, item, target.getId());
        }
    }

    private void createMergedRecord(String pid, String patientName, Date minuteTime,
                                    String combinedDesc, Recorder recorder, List<SkinItem> items) {
        NurseRecords newRecord = new NurseRecords();
        newRecord.setPid(pid);
        newRecord.setName(patientName);
        newRecord.setUsername(recorder.username);
        newRecord.setUserId(recorder.userId);
        newRecord.setTrueName(recorder.trueName);
        newRecord.setProfessions(recorder.professions);
        newRecord.setDesc(combinedDesc);
        newRecord.setTime(minuteTime);
        newRecord.setCreateTime(new Date());
        newRecord.setValid(true);
        newRecord.setUseTimes(0);
        newRecord.setDrugExeManualFlag(false);
        newRecord.setAutoSyn(true);
        NurseRecords saved = nurseRecordsRepository.insert(newRecord);

        for (SkinItem item : items) {
            insertHistory(pid, item, saved.getId());
        }
    }

    /**
     * 写同步历史。tubeRecordTime 存明细原始时间，作为「是否已同步过」的判断依据。
     */
    private void insertHistory(String pid, SkinItem item, String nurseRecordId) {
        NurseRecordsHistory history = new NurseRecordsHistory();
        history.setPid(pid);
        history.setSyncType(SYNC_TYPE_OTHER_SKIN);
        history.setTubeExeId(item.itemId);
        history.setTubeType(SYNC_TYPE_OTHER_SKIN);
        history.setShiftType("");
        history.setTubeRecordTime(item.recordTime);
        history.setNurseRecordId(nurseRecordId);
        history.setSyncContent(item.desc);
        history.setSyncTime(new Date());
        nurseRecordsHistoryRepository.insert(history);
    }

    private Map<String, Account> loadAccounts(Set<String> userIds) {
        Map<String, Account> accountMap = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return accountMap;
        }
        for (Account account : accountRepository.findByIdIn(userIds)) {
            accountMap.put(account.getId(), account);
        }
        return accountMap;
    }

    // ══════════════════════════════════════════════════════════════
    //  数据清洗
    // ══════════════════════════════════════════════════════════════

    /**
     * 清理 skinMessage 多余标点符号。
     *
     * <p>如「无红肿、；护理措施」→「无红肿；护理措施」：列表项末尾多余的顿号/逗号紧挨分段分号时去掉，
     * 多个连续分号折叠为一个，首尾多余分隔符去掉。列表项之间的顿号（如「透明贴、美皮康银离子」）保留。</p>
     */
    static String cleanPunctuation(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String s = raw.trim();
        // 列表项末尾多余的顿号/逗号紧挨分段分号：无红肿、； → 无红肿；
        s = s.replaceAll("[、，,]+(?=[；;])", "");
        // 分段分号后紧跟的顿号/逗号也算多余：；、 → ；
        s = s.replaceAll("[；;][、，,]+", "；");
        // 连续多个分段分号折叠为一个
        s = s.replaceAll("[；;]{2,}", "；");
        // 去掉首尾多余的分段分号/顿号
        s = s.replaceAll("^[；;、，,\\s]+", "");
        s = s.replaceAll("[；;、，,\\s]+$", "");
        return s;
    }

    /**
     * status=invalid 表示无效（已作废）数据，需要过滤不同步。
     */
    static boolean isInvalidStatus(Document doc) {
        String status = doc != null ? doc.getString("status") : null;
        return status != null && "invalid".equalsIgnoreCase(status.trim());
    }

    /**
     * 明细时间：recordTime（user 所称的 time），缺失时退 createdTime。
     */
    private Date getItemTime(Document item) {
        Date t = item.getDate("recordTime");
        if (t == null) {
            t = item.getDate("createdTime");
        }
        return t;
    }

    /**
     * 安全获取 Document 中的 List 字段。
     */
    @SuppressWarnings("unchecked")
    private List<Document> getList(Document doc, String key) {
        Object value = doc.get(key);
        if (value instanceof List) {
            return (List<Document>) value;
        }
        return Collections.emptyList();
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
