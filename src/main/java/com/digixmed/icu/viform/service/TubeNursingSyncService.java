package com.digixmed.icu.viform.service;

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

            // 4. 批量查询已有的同步历史
            List<NurseRecordsHistory> histories = nurseRecordsHistoryRepository.findByPidIn(pids);
            Map<String, NurseRecordsHistory> historyMap = new HashMap<>();
            for (NurseRecordsHistory history : histories) {
                String key = buildHistoryKey(history.getTubeExeId(), history.getShiftType(),
                        history.getTubeRecordTime());
                historyMap.put(key, history);
            }
            log.info("[TubeNursingSync] nurseRecordsHistory 命中: {} 条", histories.size());

            // 5. 按患者分组处理
            Map<String, List<Document>> tubeExeByPid = new HashMap<>();
            for (Document doc : tubeExeDocs) {
                String pid = doc.getString("pid");
                tubeExeByPid.computeIfAbsent(pid, k -> new ArrayList<>()).add(doc);
            }

            for (Map.Entry<String, List<Document>> entry : tubeExeByPid.entrySet()) {
                String pid = entry.getKey();
                List<Document> patientTubes = entry.getValue();
                String patientName = patientNameMap.getOrDefault(pid, "");

                for (Document tubeDoc : patientTubes) {
                    totalTubes.incrementAndGet();

                    try {
                        String tubeId = tubeDoc.getObjectId("_id").toHexString();
                        String tubeName = tubeDoc.getString("name");
                        Date startTime = tubeDoc.getDate("startTime");

                        // 获取 tubeRecordList
                        List<Document> tubeRecordList = getList(tubeDoc, "tubeRecordList");
                        if (tubeRecordList == null || tubeRecordList.isEmpty()) {
                            continue;
                        }

                        // 按班次分组，取每班第一条有效记录
                        Map<String, Document> firstRecordByShift = selectFirstRecordByShift(tubeRecordList);

                        for (Map.Entry<String, Document> shiftEntry : firstRecordByShift.entrySet()) {
                            String shiftType = shiftEntry.getKey();
                            Document recordDoc = shiftEntry.getValue();

                            try {
                                Date recordTime = recordDoc.getDate("time");

                                // 检查是否已同步
                                String historyKey = buildHistoryKey(tubeId, shiftType, recordTime);
                                NurseRecordsHistory existingHistory = historyMap.get(historyKey);

                                // 拼接描述内容
                                String desc = buildDesc(tubeName, startTime, recordDoc, configMap);

                                if (existingHistory != null) {
                                    // 已存在 - 检查内容是否相同
                                    if (desc.equals(existingHistory.getSyncContent())) {
                                        skippedRecords.incrementAndGet();
                                        continue;
                                    }

                                    // 内容不同 - 更新
                                    NurseRecords nurseRecord = nurseRecordsRepository
                                            .findById(existingHistory.getNurseRecordId())
                                            .orElse(null);
                                    if (nurseRecord != null) {
                                        nurseRecord.setDesc(desc);
                                        nurseRecord.setTime(recordTime);
                                        nurseRecordsRepository.save(nurseRecord);

                                        existingHistory.setSyncContent(desc);
                                        existingHistory.setSyncTime(new Date());
                                        nurseRecordsHistoryRepository.save(existingHistory);

                                        updatedRecords.incrementAndGet();
                                        log.info("[TubeNursingSync] 更新护理记录 pid={} tubeType={} shift={}",
                                                pid, tubeName, shiftType);
                                    } else {
                                        // 护理记录被删除，重新创建
                                        NurseRecords newRecord = createNurseRecord(pid, patientName,
                                                tubeId, tubeName, recordDoc, desc);
                                        NurseRecords saved = nurseRecordsRepository.insert(newRecord);

                                        existingHistory.setNurseRecordId(saved.getId());
                                        existingHistory.setSyncContent(desc);
                                        existingHistory.setSyncTime(new Date());
                                        nurseRecordsHistoryRepository.save(existingHistory);

                                        syncedRecords.incrementAndGet();
                                    }
                                } else {
                                    // 新增
                                    NurseRecords newRecord = createNurseRecord(pid, patientName,
                                            tubeId, tubeName, recordDoc, desc);
                                    NurseRecords saved = nurseRecordsRepository.insert(newRecord);

                                    NurseRecordsHistory newHistory = new NurseRecordsHistory();
                                    newHistory.setPid(pid);
                                    newHistory.setTubeExeId(tubeId);
                                    newHistory.setTubeType(tubeName);
                                    newHistory.setShiftType(shiftType);
                                    newHistory.setTubeRecordTime(recordTime);
                                    newHistory.setNurseRecordId(saved.getId());
                                    newHistory.setSyncTime(new Date());
                                    newHistory.setSyncContent(desc);
                                    nurseRecordsHistoryRepository.insert(newHistory);

                                    syncedRecords.incrementAndGet();
                                    log.info("[TubeNursingSync] 新增护理记录 pid={} tubeType={} shift={}",
                                            pid, tubeName, shiftType);
                                }
                            } catch (Exception e) {
                                log.error("[TubeNursingSync] 同步单条记录异常 pid={} tubeType={} shift={}",
                                        pid, tubeName, shiftType, e);
                                failedRecords.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        log.error("[TubeNursingSync] 处理管道异常 pid={}", pid, e);
                        failedRecords.incrementAndGet();
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

    /**
     * 构建历史记录唯一键。
     */
    private String buildHistoryKey(String tubeExeId, String shiftType, Date tubeRecordTime) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmm");
        return tubeExeId + "_" + shiftType + "_" + sdf.format(tubeRecordTime);
    }

    /**
     * 拼接护理记录描述。
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

    /**
     * 创建护理记录对象。
     */
    private NurseRecords createNurseRecord(String pid, String patientName,
                                            String tubeId, String tubeName,
                                            Document recordDoc, String desc) {
        NurseRecords nurseRecord = new NurseRecords();
        nurseRecord.setPid(pid);
        nurseRecord.setName(patientName);
        nurseRecord.setUsername(recordDoc.getString("recordUserName"));
        nurseRecord.setUserId(recordDoc.getString("recordUser"));
        nurseRecord.setDesc(desc);
        nurseRecord.setTime(recordDoc.getDate("time"));
        nurseRecord.setCreateTime(new Date());
        nurseRecord.setValid(true);
        nurseRecord.setAutoSyn(true);
        nurseRecord.setUseTimes(0);
        nurseRecord.setDrugExeManualFlag(false);
        return nurseRecord;
    }
}
