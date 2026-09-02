package com.digixmed.icu.viform.service;

import com.digixmed.icu.viform.common.TimeUtils;
import com.digixmed.icu.viform.config.TubeNursingSyncProperties;
import com.digixmed.icu.viform.entity.Account;
import com.digixmed.icu.viform.entity.ConfigTubeView;
import com.digixmed.icu.viform.entity.NurseRecords;
import com.digixmed.icu.viform.entity.NurseRecordsHistory;
import com.digixmed.icu.viform.entity.Patient;
import com.digixmed.icu.viform.entity.TubeFieldConfig;
import com.digixmed.icu.viform.repository.smartcare.AccountRepository;
import com.digixmed.icu.viform.repository.smartcare.ConfigTubeViewRepository;
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
 * 管道护理记录同步服务。
 *
 * <p>读取在院患者的管道护理记录（tubeExe），
 * 按班次时间段取每条管道的第一条有效记录，
 * 结合 configTubeView 配置映射字段名称，
 * 拼接成护理记录描述后写入 nurseRecords。</p>
 *
 * <p>核心策略：</p>
 * <ul>
 *   <li>每个管道每个班次只同步第一条有效记录</li>
 *   <li>根据 configTubeView 配置动态映射字段名称</li>
 *   <li>通过 nurseRecordsHistory 实现去重和覆盖更新</li>
 *   <li>使用 MongoTemplate 查询原始 Document，支持动态字段</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TubeNursingSyncService {

    private final PatientRepository patientRepository;
    private final ConfigTubeViewRepository configTubeViewRepository;
    private final NurseRecordsRepository nurseRecordsRepository;
    private final NurseRecordsHistoryRepository nurseRecordsHistoryRepository;
    private final AccountRepository accountRepository;
    private final TubeNursingSyncProperties properties;
    private final MongoTemplate smartCareMongoTemplate;

    /** 防重入锁 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 在院状态常量 */
    private static final String STATUS_ADMITTED = "admitted";

    /** 班次类型常量 */
    private static final String SHIFT_MORNING = "MORNING";
    private static final String SHIFT_AFTERNOON = "AFTERNOON";
    private static final String SHIFT_NIGHT = "NIGHT";

    /** 同步类型标识 */
    private static final String SYNC_TYPE_PIPE = "PIPE";

    /** 合并后各管道描述之间的分隔符 */
    private static final String DESC_SEPARATOR = " ";

    /** 同步结果统计 */
    public static class SyncResult {
        public final int totalPatients;
        public final int totalTubes;
        public final int syncedRecords;
        public final int skippedRecords;
        public final int updatedRecords;
        public final int failedRecords;

        public SyncResult(int totalPatients, int totalTubes, int syncedRecords,
                          int skippedRecords, int updatedRecords, int failedRecords) {
            this.totalPatients = totalPatients;
            this.totalTubes = totalTubes;
            this.syncedRecords = syncedRecords;
            this.skippedRecords = skippedRecords;
            this.updatedRecords = updatedRecords;
            this.failedRecords = failedRecords;
        }
    }

    /**
     * 合并单元：同一患者、同一分钟内的所有管道记录合并成一条护理记录。
     */
    private static class MergeUnit {
        /** 截断到分钟的时间点 */
        Date minuteTime;
        /** 班次（取该分钟内第一条记录的班次） */
        String shiftType;
        /** 各管道的描述片段 */
        final List<DescPart> parts = new ArrayList<>();
        /** 操作人（取第一条非空的） */
        String recordUserName;
        String recordUserId;
        /** 账户用户名和职业 */
        String accountUsername;
        String accountProfession;
        /** 参与合并的 tubeExe id 集合，用于回写 history */
        final Set<String> tubeExeIds = new LinkedHashSet<>();

        /**
         * 拼接合并后的描述。
         *
         * <p>必须先按 sortKey 排序：Mongo 返回顺序不保证稳定，
         * 若不排序会导致 syncContent 每轮抖动，触发无意义的重复更新。</p>
         */
        String buildDesc() {
            List<DescPart> sorted = new ArrayList<>(parts);
            sorted.sort(Comparator
                    .comparing((DescPart p) -> p.sortKey, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(p -> p.desc, Comparator.nullsLast(Comparator.naturalOrder())));
            StringBuilder sb = new StringBuilder();
            for (DescPart p : sorted) {
                if (sb.length() > 0) {
                    sb.append(DESC_SEPARATOR);
                }
                sb.append(p.desc);
            }
            return sb.toString();
        }
    }

    /** 单条管道的描述片段 */
    private static class DescPart {
        final String sortKey;
        final String desc;

        DescPart(String sortKey, String desc) {
            this.sortKey = sortKey;
            this.desc = desc;
        }
    }

    /**
     * 执行全量同步（按患者分批处理，降低数据库压力）。
     */
    public SyncResult syncAllAdmittedPatients() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[TubeNursingSync] 上一轮任务尚未完成，跳过本次");
            return new SyncResult(0, 0, 0, 0, 0, 0);
        }

        AtomicInteger totalPatients = new AtomicInteger();
        AtomicInteger totalTubes = new AtomicInteger();
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
            log.info("[TubeNursingSync] 开始同步 admittedPatients={}", patients.size());

            if (patients.isEmpty()) {
                return new SyncResult(0, 0, 0, 0, 0, 0);
            }

            // 构建 pid → patientName 映射
            Map<String, String> patientNameMap = new HashMap<>();
            for (Patient p : patients) {
                patientNameMap.put(p.getId(), p.getName());
            }

            // 2. 查询管道配置（一次性，数据量小）
            List<ConfigTubeView> configViews = configTubeViewRepository.findByValidTrue();
            Map<String, ConfigTubeView> configMap = new HashMap<>();
            for (ConfigTubeView config : configViews) {
                configMap.put(config.getTubeType(), config);
            }
            log.info("[TubeNursingSync] configTubeView 命中: {} 条", configViews.size());

            // 3. 计算回溯时间
            Calendar syncCalendar = Calendar.getInstance();
            syncCalendar.add(Calendar.DAY_OF_MONTH, -properties.getSyncDays());
            Date syncStartTime = syncCalendar.getTime();

            // 4. 按批次处理患者
            int batchSize = properties.getBatchSize();
            List<List<String>> pidBatches = partition(
                    patients.stream().map(Patient::getId).collect(Collectors.toList()), batchSize);
            int totalBatches = pidBatches.size();
            log.info("[TubeNursingSync] 分批处理: 患者数={}, 批大小={}, 总批数={}",
                    patients.size(), batchSize, totalBatches);

            for (int i = 0; i < pidBatches.size(); i++) {
                List<String> batchPids = pidBatches.get(i);
                int batchNo = i + 1;
                log.info("[TubeNursingSync] 批次 {}/{} 开始, 患者数={}", batchNo, totalBatches, batchPids.size());

                try {
                    // 按批次查询 tubeExe（查询所有在院患者的管道，不按startTime过滤）
                    // startTime过滤会在处理每条记录时根据record.time来判断
                    Query tubeQuery = new Query();
                    tubeQuery.addCriteria(Criteria.where("pid").in(batchPids));
                    List<Document> tubeExeDocs = smartCareMongoTemplate.find(tubeQuery, Document.class, "tubeExe");

                    if (tubeExeDocs.isEmpty()) {
                        log.info("[TubeNursingSync] 批次 {}/{} tubeExe 无数据，跳过", batchNo, totalBatches);
                        continue;
                    }

                    // 按批次查询历史
                    List<NurseRecordsHistory> histories = nurseRecordsHistoryRepository.findByPidIn(batchPids);
                    Map<String, NurseRecordsHistory> historyMap = histories.stream()
                            .filter(h -> SYNC_TYPE_PIPE.equals(h.getSyncType()))
                            .filter(h -> h.getTubeRecordTime() != null)
                            .collect(Collectors.toMap(
                                    h -> buildMergedHistoryKey(h.getPid(), TimeUtils.truncateToMinute(h.getTubeRecordTime())),
                                    h -> h,
                                    (a, b) -> a.getSyncTime() != null && b.getSyncTime() != null
                                            && a.getSyncTime().after(b.getSyncTime()) ? a : b));

                    // 按患者分组处理
                    Map<String, List<Document>> tubeExeByPid = new HashMap<>();
                    for (Document doc : tubeExeDocs) {
                        String pid = doc.getString("pid");
                        tubeExeByPid.computeIfAbsent(pid, k -> new ArrayList<>()).add(doc);
                    }

                    for (Map.Entry<String, List<Document>> entry : tubeExeByPid.entrySet()) {
                        String pid = entry.getKey();
                        List<Document> tubeExeDocs4Pid = entry.getValue();
                        String patientName = patientNameMap.getOrDefault(pid, "");

                        try {
                            Map<String, MergeUnit> units = collectMergeUnits(pid, tubeExeDocs4Pid, configMap, syncStartTime);
                            persistMergeUnits(pid, patientName, units, historyMap,
                                    syncedRecords, skippedRecords, updatedRecords, failedRecords);
                        } catch (Exception e) {
                            log.error("[TubeNursingSync] 处理患者管道异常 pid={}", pid, e);
                            failedRecords.incrementAndGet();
                        }
                    }

                    log.info("[TubeNursingSync] 批次 {}/{} 完成", batchNo, totalBatches);

                } catch (Exception e) {
                    log.error("[TubeNursingSync] 批次 {}/{} 异常", batchNo, totalBatches, e);
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

            log.info("[TubeNursingSync] 完成 syncedRecords={} skippedRecords={} updatedRecords={} failedRecords={}",
                    syncedRecords.get(), skippedRecords.get(), updatedRecords.get(), failedRecords.get());

            return new SyncResult(totalPatients.get(), totalTubes.get(),
                    syncedRecords.get(), skippedRecords.get(), updatedRecords.get(), failedRecords.get());

        } catch (Exception e) {
            log.error("[TubeNursingSync] 同步异常", e);
            return new SyncResult(totalPatients.get(), totalTubes.get(),
                    syncedRecords.get(), skippedRecords.get(), updatedRecords.get(), failedRecords.get());
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
     * 安全获取 Document 中的 List 字段。
     */
    @SuppressWarnings("unchecked")
    private List<Document> getList(Document doc, String key) {
        Object value = doc.get(key);
        if (value instanceof List) {
            return (List<Document>) value;
        }
        return null;
    }

    /**
     * 按班次分组，取每班第一条有效记录。
     */
    private Map<String, Document> selectFirstRecordByShift(List<Document> tubeRecordList) {
        Map<String, Document> result = new HashMap<>();

        for (Document record : tubeRecordList) {
            // 只处理有效记录
            Boolean valid = record.getBoolean("valid");
            if (!Boolean.TRUE.equals(valid)) {
                continue;
            }

            Date time = record.getDate("time");
            if (time == null) {
                continue;
            }

            String shiftType = getShiftType(time);

            // 取每班时间最早的记录
            result.merge(shiftType, record, (existing, current) -> {
                Date existingTime = existing.getDate("time");
                Date currentTime = current.getDate("time");
                return currentTime.before(existingTime) ? current : existing;
            });
        }

        return result;
    }

    /**
     * 根据时间获取班次类型。
     */
    private String getShiftType(Date time) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(time);
        int hour = cal.get(Calendar.HOUR_OF_DAY);

        if (hour >= 8 && hour < 16) {
            return SHIFT_MORNING;    // 早班 08:00-15:59
        } else if (hour >= 16 && hour < 24) {
            return SHIFT_AFTERNOON;  // 中班 16:00-23:59
        } else {
            return SHIFT_NIGHT;      // 晚班 00:00-07:59
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  合并相关
    // ══════════════════════════════════════════════════════════════

    /** 合并键：同一患者同一分钟归为一组 */
    private String buildMergeKey(String pid, Date minuteTime) {
        return pid + "|" + new SimpleDateFormat("yyyyMMddHHmm").format(minuteTime);
    }

    /**
     * 合并后的历史去重键。
     *
     * <p>格式：{pid}|PIPE|{yyyyMMddHHmm}，与合并粒度一致。
     * 注意此键与旧版 {tubeExeId}|{shiftType}|{时间} 不兼容，上线需数据迁移。</p>
     */
    private String buildMergedHistoryKey(String pid, Date minuteTime) {
        return pid + "|" + SYNC_TYPE_PIPE + "|"
                + new SimpleDateFormat("yyyyMMddHHmm").format(minuteTime);
    }

    /**
     * 收集某患者所有管道记录，按分钟合并。
     *
     * @param pid         患者 ID
     * @param tubeExeDocs 该患者的 tubeExe 原始文档
     * @param tubeViewMap tubeType → 字段配置
     * @param syncStartTime 同步起始时间，只处理此时间之后的记录
     * @return mergeKey → 合并单元
     */
    private Map<String, MergeUnit> collectMergeUnits(String pid,
                                                     List<Document> tubeExeDocs,
                                                     Map<String, ConfigTubeView> tubeViewMap,
                                                     Date syncStartTime) {
        Map<String, MergeUnit> units = new LinkedHashMap<>();

        for (Document tubeExeDoc : tubeExeDocs) {
            String tubeExeId = tubeExeDoc.getObjectId("_id") != null
                    ? tubeExeDoc.getObjectId("_id").toHexString()
                    : null;
            // 使用 type 字段匹配 configTubeView，而不是 name 字段
            String tubeType = tubeExeDoc.getString("type");
            if (!StringUtils.hasText(tubeType)) {
                continue;
            }

            ConfigTubeView tubeView = tubeViewMap.get(tubeType);
            if (tubeView == null) {
                log.debug("[TubeNursingSync] tubeType={} 无 configTubeView 配置，跳过", tubeType);
                continue;
            }

            List<Document> recordList = getList(tubeExeDoc, "tubeRecordList");
            if (recordList == null || recordList.isEmpty()) {
                continue;
            }

            // 处理每条有效记录，不再按班次筛选
            for (Document record : recordList) {
                Boolean valid = record.getBoolean("valid");
                if (!Boolean.TRUE.equals(valid)) {
                    continue;
                }

                Date rawTime = record.getDate("time");
                if (rawTime == null) {
                    continue;
                }

                // 过滤同步时间范围外的记录
                if (rawTime.before(syncStartTime)) {
                    continue;
                }

                Date minuteTime = TimeUtils.truncateToMinute(rawTime);

                // 使用 name 字段作为展示名称，而不是 type 字段
                String tubeName = tubeExeDoc.getString("name");
                String desc = buildDesc(tubeName, record, tubeViewMap);
                if (!StringUtils.hasText(desc)) {
                    continue;
                }

                String shiftType = getShiftType(rawTime);
                String mergeKey = buildMergeKey(pid, minuteTime);
                MergeUnit unit = units.computeIfAbsent(mergeKey, k -> {
                    MergeUnit u = new MergeUnit();
                    u.minuteTime = minuteTime;
                    u.shiftType = shiftType;
                    return u;
                });

                // 排序键用 tubeType，保证「尿管+胃管」与「胃管+尿管」拼出相同内容
                unit.parts.add(new DescPart(tubeType, desc));
                if (tubeExeId != null) {
                    unit.tubeExeIds.add(tubeExeId);
                }
                if (!StringUtils.hasText(unit.recordUserName)) {
                    unit.recordUserName = record.getString("recordUserName");
                    unit.recordUserId = record.getString("recordUser");
                    // 查询账户获取 username 和 profession
                    if (StringUtils.hasText(unit.recordUserId)) {
                        Account account = accountRepository.findById(unit.recordUserId).orElse(null);
                        if (account != null) {
                            unit.accountUsername = account.getUsername();
                            unit.accountProfession = account.getProfession();
                        }
                    }
                }
            }
        }
        return units;
    }

    /**
     * 将合并单元写入 nurseRecords，并维护 nurseRecordsHistory。
     */
    private void persistMergeUnits(String pid,
                                   String patientName,
                                   Map<String, MergeUnit> units,
                                   Map<String, NurseRecordsHistory> historyMap,
                                   AtomicInteger synced,
                                   AtomicInteger skipped,
                                   AtomicInteger updated,
                                   AtomicInteger failed) {

        for (MergeUnit unit : units.values()) {
            try {
                String historyKey = buildMergedHistoryKey(pid, unit.minuteTime);
                String desc = unit.buildDesc();
                String tubeExeIdJoined = String.join(",", unit.tubeExeIds);

                NurseRecordsHistory existing = historyMap.get(historyKey);

                // 核心逻辑：日志表有记录 = 已同步过 = 直接跳过
                if (existing != null) {
                    skipped.incrementAndGet();
                    log.info("[TubeNursingSync] 已同步过，跳过 pid={}, time={}", pid, unit.minuteTime);
                    continue;
                }

                // 无历史记录，检查同一时间点是否已有记录（可能是用户手写的，也可能是其他同步的）
                NurseRecords existingAtTime = findExistingAutoSynRecord(pid, unit.minuteTime);

                if (existingAtTime != null) {
                    // 检查是否是用户手写的
                    if (isUserWritten(existingAtTime)) {
                        // 用户手写记录 → 追加管道数据
                        appendToExistingRecord(existingAtTime, desc, unit, pid, tubeExeIdJoined);
                        synced.incrementAndGet();
                        log.info("[TubeNursingSync] 追加管道数据到用户记录 pid={}, nurseRecordId={}", pid, existingAtTime.getId());
                    } else {
                        // 其他同步记录 → 管道数据拼接到已有记录前面
                        String oldDesc = existingAtTime.getDesc();
                        String mergedDesc = StringUtils.hasText(desc)
                                ? desc + "\n" + oldDesc
                                : oldDesc;
                        existingAtTime.setDesc(mergedDesc);
                        existingAtTime.setUsername(unit.recordUserName);
                        existingAtTime.setUserId(unit.recordUserId);
                        existingAtTime.setTrueName(unit.accountUsername);
                        existingAtTime.setProfessions(unit.accountProfession);
                        nurseRecordsRepository.save(existingAtTime);

                        // 创建管道 history
                        NurseRecordsHistory pipeHistory = new NurseRecordsHistory();
                        pipeHistory.setPid(pid);
                        pipeHistory.setSyncType(SYNC_TYPE_PIPE);
                        pipeHistory.setTubeExeId(tubeExeIdJoined);
                        pipeHistory.setShiftType(unit.shiftType);
                        pipeHistory.setTubeRecordTime(unit.minuteTime);
                        pipeHistory.setNurseRecordId(existingAtTime.getId());
                        pipeHistory.setSyncContent(desc);
                        pipeHistory.setSyncTime(new Date());
                        nurseRecordsHistoryRepository.insert(pipeHistory);

                        synced.incrementAndGet();
                        log.info("[TubeNursingSync] 管道数据拼接到同步记录 pid={}, nurseRecordId={}", pid, existingAtTime.getId());
                    }
                } else {
                    // 无已有记录 → 新建（标记为自动同步）
                    NurseRecords created = createMergedNurseRecord(pid, patientName, unit, desc);
                    created.setAutoSyn(true);  // 标记为自动同步
                    NurseRecords saved = nurseRecordsRepository.insert(created);

                    NurseRecordsHistory history = new NurseRecordsHistory();
                    history.setPid(pid);
                    history.setSyncType(SYNC_TYPE_PIPE);
                    history.setTubeExeId(tubeExeIdJoined);
                    history.setShiftType(unit.shiftType);
                    history.setTubeRecordTime(unit.minuteTime);
                    history.setNurseRecordId(saved.getId());
                    history.setSyncContent(desc);
                    history.setSyncTime(new Date());
                    nurseRecordsHistoryRepository.insert(history);

                    synced.incrementAndGet();
                    log.info("[TubeNursingSync] 新增同步护理记录 pid={}, time={}, 管道数={}",
                            pid, unit.minuteTime, unit.parts.size());
                }

            } catch (Exception e) {
                failed.incrementAndGet();
                log.error("[TubeNursingSync] 合并记录同步失败 pid={}, time={}", pid, unit.minuteTime, e);
            }
        }
    }

    /**
     * 构建合并后的护理记录。
     */
    private NurseRecords createMergedNurseRecord(String pid,
                                                 String patientName,
                                                 MergeUnit unit,
                                                 String desc) {
        NurseRecords nurseRecord = new NurseRecords();
        nurseRecord.setPid(pid);
        nurseRecord.setName(patientName);
        nurseRecord.setUsername(unit.recordUserName);
        nurseRecord.setUserId(unit.recordUserId);
        nurseRecord.setTrueName(unit.accountUsername);
        nurseRecord.setProfessions(unit.accountProfession);
        nurseRecord.setDesc(desc);
        nurseRecord.setTime(unit.minuteTime);
        nurseRecord.setCreateTime(new Date());
        nurseRecord.setValid(true);
        nurseRecord.setUseTimes(0);
        nurseRecord.setDrugExeManualFlag(false);
        nurseRecord.setAutoSyn(false);
        return nurseRecord;
    }

    /**
     * 拼接护理记录描述（单条管道）。
     */
    /**
     * 拼接护理记录描述（单条管道）。
     *
     * <p>格式：{管道名}：{字段名}:{值};{字段名}:{值};</p>
     * <p>例：胃管：置入长度:55cm;固定情况:妥善固定;</p>
     *
     * <p>按业务要求不再输出「时间」和「操作人」：
     * 时间已由护理记录本身的 time 字段体现，操作人由 username 字段体现，
     * 描述里重复出现会让合并后的多管道记录冗长。</p>
     *
     * @return 描述文本；无任何有效字段时返回 null（调用方跳过该条）
     */
    private String buildDesc(String tubeName, Document recordDoc,
                             Map<String, ConfigTubeView> configMap) {
        ConfigTubeView config = configMap.get(tubeName);
        if (config == null || config.getTubeRecordFieldConfigList() == null) {
            return null;
        }

        StringBuilder fields = new StringBuilder();
        for (TubeFieldConfig fieldConfig : config.getTubeRecordFieldConfigList()) {
            String field = fieldConfig.getField();
            Object value = recordDoc.get(field);
            if (value != null && StringUtils.hasText(value.toString())) {
                fields.append(fieldConfig.getName())
                      .append(":")
                      .append(value.toString().trim());
                // 如果配置了单位，拼接单位
                if (StringUtils.hasText(fieldConfig.getUnit())) {
                    fields.append(fieldConfig.getUnit());
                }
                fields.append(";");
            }
        }

        // 去掉时间和操作人后，若配置字段全为空则整条无意义，不生成护理记录
        if (fields.length() == 0) {
            return null;
        }

        return tubeName + "：" + fields;
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
     *
     * <p>保存前重新查询数据库获取最新 desc，避免用户正在编辑时的竞态问题：
     * 用户写了一部分 → 同步读到旧 desc → 用户继续写 → 同步保存覆盖用户新内容。
     * 重新查询可以拿到用户最新的 desc，再在其基础上追加。</p>
     */
    private void appendToExistingRecord(NurseRecords existingRecord, String desc,
                                        MergeUnit unit, String pid, String tubeExeIdJoined) {
        // [修改记录] 2026-08-22 易绍龙: 保存前重新查询，获取用户最新编辑内容
        NurseRecords latest = nurseRecordsRepository.findById(existingRecord.getId()).orElse(existingRecord);
        String oldDesc = latest.getDesc();

        // 防止重复追加：如果 desc 中已经包含同步内容，不再追加
        if (StringUtils.hasText(oldDesc) && oldDesc.contains(desc)) {
            log.info("[TubeNursingSync] 同步内容已存在于记录中，跳过追加 pid={}, nurseRecordId={}",
                    pid, existingRecord.getId());
            return;
        }

        String mergedDesc = StringUtils.hasText(oldDesc)
                ? oldDesc + "\n" + desc
                : desc;
        latest.setDesc(mergedDesc);
        // [修改记录] 2026-08-22 易绍龙: 追加时不覆盖用户的 username/userId/trueName/professions
        nurseRecordsRepository.save(latest);

        // 创建管道 history
        NurseRecordsHistory pipeHistory = new NurseRecordsHistory();
        pipeHistory.setPid(pid);
        pipeHistory.setSyncType(SYNC_TYPE_PIPE);
        pipeHistory.setTubeExeId(tubeExeIdJoined);
        pipeHistory.setShiftType(unit.shiftType);
        pipeHistory.setTubeRecordTime(unit.minuteTime);
        pipeHistory.setNurseRecordId(existingRecord.getId());
        pipeHistory.setSyncContent(desc);
        pipeHistory.setSyncTime(new Date());
        nurseRecordsHistoryRepository.insert(pipeHistory);
    }

    /**
     * 查找指定患者在同一时间点已有的护理记录（管道或牙齿）。
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

}
