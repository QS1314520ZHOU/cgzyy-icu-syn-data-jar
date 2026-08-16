package com.digixmed.icu.viform.service;

import com.digixmed.icu.viform.common.TimeUtils;
import com.digixmed.icu.viform.config.TubeNursingSyncProperties;
import com.digixmed.icu.viform.entity.ConfigTubeView;
import com.digixmed.icu.viform.entity.NurseRecords;
import com.digixmed.icu.viform.entity.NurseRecordsHistory;
import com.digixmed.icu.viform.entity.Patient;
import com.digixmed.icu.viform.entity.TubeFieldConfig;
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
     * 执行全量同步。
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

            List<String> pids = patients.stream()
                    .map(Patient::getId)
                    .collect(Collectors.toList());

            // 构建 pid → patientName 映射
            Map<String, String> patientNameMap = new HashMap<>();
            for (Patient p : patients) {
                patientNameMap.put(p.getId(), p.getName());
            }

            // 2. 使用 MongoTemplate 查询管道护理记录（仅查询最近N天）
            Calendar syncCalendar = Calendar.getInstance();
            syncCalendar.add(Calendar.DAY_OF_MONTH, -properties.getSyncDays());
            Date syncStartTime = syncCalendar.getTime();

            Query tubeQuery = new Query();
            tubeQuery.addCriteria(Criteria.where("pid").in(pids));
            tubeQuery.addCriteria(Criteria.where("startTime").gte(syncStartTime));
            List<Document> tubeExeDocs = smartCareMongoTemplate.find(tubeQuery, Document.class, "tubeExe");
            log.info("[TubeNursingSync] tubeExe 命中: {} 条", tubeExeDocs.size());

            if (tubeExeDocs.isEmpty()) {
                return new SyncResult(totalPatients.get(), 0, 0, 0, 0, 0);
            }

            // 3. 查询管道配置
            List<ConfigTubeView> configViews = configTubeViewRepository.findByValidTrue();
            Map<String, ConfigTubeView> configMap = new HashMap<>();
            for (ConfigTubeView config : configViews) {
                configMap.put(config.getTubeType(), config);
            }
            log.info("[TubeNursingSync] configTubeView 命中: {} 条", configViews.size());

            // 4. 批量查询已有的同步历史（合并键）
            List<NurseRecordsHistory> histories = nurseRecordsHistoryRepository.findByPidIn(pids);
            Map<String, NurseRecordsHistory> historyMap = histories.stream()
                    .filter(h -> SYNC_TYPE_PIPE.equals(h.getSyncType()))
                    .filter(h -> h.getTubeRecordTime() != null)
                    .collect(Collectors.toMap(
                            h -> buildMergedHistoryKey(h.getPid(), TimeUtils.truncateToMinute(h.getTubeRecordTime())),
                            h -> h,
                            (a, b) -> a.getSyncTime() != null && b.getSyncTime() != null
                                    && a.getSyncTime().after(b.getSyncTime()) ? a : b));
            log.info("[TubeNursingSync] nurseRecordsHistory 命中: {} 条", histories.size());

            // 5. 按患者分组处理
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
                    Map<String, MergeUnit> units = collectMergeUnits(pid, tubeExeDocs4Pid, configMap);
                    persistMergeUnits(pid, patientName, units, historyMap,
                            syncedRecords, skippedRecords, updatedRecords, failedRecords);
                } catch (Exception e) {
                    log.error("[TubeNursingSync] 处理患者管道异常 pid={}", pid, e);
                    failedRecords.incrementAndGet();
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
     * @return mergeKey → 合并单元
     */
    private Map<String, MergeUnit> collectMergeUnits(String pid,
                                                     List<Document> tubeExeDocs,
                                                     Map<String, ConfigTubeView> tubeViewMap) {
        Map<String, MergeUnit> units = new LinkedHashMap<>();

        for (Document tubeExeDoc : tubeExeDocs) {
            String tubeExeId = tubeExeDoc.getObjectId("_id") != null
                    ? tubeExeDoc.getObjectId("_id").toHexString()
                    : null;
            String tubeType = tubeExeDoc.getString("name");
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

            // 每个班次取第一条（沿用现有班次筛选逻辑）
            Map<String, Document> firstByShift = selectFirstRecordByShift(recordList);

            for (Map.Entry<String, Document> shiftEntry : firstByShift.entrySet()) {
                String shiftType = shiftEntry.getKey();
                Document record = shiftEntry.getValue();

                Date rawTime = record.getDate("time");
                if (rawTime == null) {
                    continue;
                }
                Date minuteTime = TimeUtils.truncateToMinute(rawTime);

                String desc = buildDesc(tubeType, tubeExeDoc.getDate("startTime"), record, tubeViewMap);
                if (!StringUtils.hasText(desc)) {
                    continue;
                }

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

                if (existing == null) {
                    NurseRecords created = createMergedNurseRecord(pid, patientName, unit, desc);
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
                    log.info("[TubeNursingSync] 新增合并护理记录 pid={}, time={}, 管道数={}",
                            pid, unit.minuteTime, unit.parts.size());
                    continue;
                }

                NurseRecords nurseRecord = existing.getNurseRecordId() == null
                        ? null
                        : nurseRecordsRepository.findById(existing.getNurseRecordId()).orElse(null);

                if (nurseRecord == null) {
                    // 护理记录已被删除，重建并回填 id
                    NurseRecords recreated = createMergedNurseRecord(pid, patientName, unit, desc);
                    NurseRecords saved = nurseRecordsRepository.insert(recreated);
                    existing.setNurseRecordId(saved.getId());
                    existing.setSyncContent(desc);
                    existing.setTubeExeId(tubeExeIdJoined);
                    existing.setTubeRecordTime(unit.minuteTime);
                    existing.setSyncTime(new Date());
                    nurseRecordsHistoryRepository.save(existing);
                    synced.incrementAndGet();
                    log.warn("[TubeNursingSync] 护理记录已丢失，重建 pid={}, time={}", pid, unit.minuteTime);
                    continue;
                }

                boolean contentSame = Objects.equals(existing.getSyncContent(), desc);
                boolean timeSame = Objects.equals(nurseRecord.getTime(), unit.minuteTime);

                if (contentSame && timeSame) {
                    skipped.incrementAndGet();
                    continue;
                }

                // 内容或时间有变化 → 更新（timeSame=false 用于抹掉历史遗留的带秒时间）
                nurseRecord.setDesc(desc);
                nurseRecord.setTime(unit.minuteTime);
                nurseRecordsRepository.save(nurseRecord);

                existing.setSyncContent(desc);
                existing.setTubeExeId(tubeExeIdJoined);
                existing.setTubeRecordTime(unit.minuteTime);
                existing.setSyncTime(new Date());
                nurseRecordsHistoryRepository.save(existing);

                updated.incrementAndGet();
                log.info("[TubeNursingSync] 更新合并护理记录 pid={}, time={}, 内容变更={}, 时间变更={}",
                        pid, unit.minuteTime, !contentSame, !timeSame);

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
        nurseRecord.setDesc(desc);
        nurseRecord.setTime(unit.minuteTime);
        nurseRecord.setCreateTime(new Date());
        nurseRecord.setValid(true);
        nurseRecord.setAutoSyn(true);
        nurseRecord.setUseTimes(0);
        nurseRecord.setDrugExeManualFlag(false);
        return nurseRecord;
    }

    /**
     * 拼接护理记录描述（单条管道）。
     */
    private String buildDesc(String tubeName, Date startTime,
                              Document recordDoc, Map<String, ConfigTubeView> configMap) {
        StringBuilder sb = new StringBuilder();

        // 管道类型：时间
        sb.append(tubeName).append("：时间 ");
        if (startTime != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm");
            sb.append(sdf.format(startTime));
        }
        sb.append(";");

        // 操作人
        String recordUserName = recordDoc.getString("recordUserName");
        if (StringUtils.hasText(recordUserName)) {
            sb.append("操作人:").append(recordUserName).append(";");
        }

        // 根据 configTubeView 配置拼接字段
        ConfigTubeView config = configMap.get(tubeName);
        if (config != null && config.getTubeRecordFieldConfigList() != null) {
            for (TubeFieldConfig fieldConfig : config.getTubeRecordFieldConfigList()) {
                String field = fieldConfig.getField();
                Object value = recordDoc.get(field);
                if (value != null && StringUtils.hasText(value.toString())) {
                    sb.append(fieldConfig.getName()).append(":").append(value).append(";");
                }
            }
        }

        return sb.toString();
    }

}
