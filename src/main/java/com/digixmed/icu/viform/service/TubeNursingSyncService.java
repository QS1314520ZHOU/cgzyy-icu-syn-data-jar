package com.digixmed.icu.viform.service;

import com.digixmed.icu.viform.config.TubeNursingSyncProperties;
import com.digixmed.icu.viform.entity.*;
import com.digixmed.icu.viform.repository.smartcare.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
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
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TubeNursingSyncService {

    private final PatientRepository patientRepository;
    private final TubeExeRepository tubeExeRepository;
    private final ConfigTubeViewRepository configTubeViewRepository;
    private final NurseRecordsRepository nurseRecordsRepository;
    private final NurseRecordsHistoryRepository nurseRecordsHistoryRepository;

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

            // 2. 批量查询管道护理记录
            List<TubeExe> tubeExes = tubeExeRepository.findByPidIn(pids);
            log.info("[TubeNursingSync] tubeExe 命中: {} 条", tubeExes.size());

            if (tubeExes.isEmpty()) {
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
            Map<String, List<TubeExe>> tubeExeByPid = new HashMap<>();
            for (TubeExe tube : tubeExes) {
                tubeExeByPid.computeIfAbsent(tube.getPid(), k -> new ArrayList<>()).add(tube);
            }

            for (Map.Entry<String, List<TubeExe>> entry : tubeExeByPid.entrySet()) {
                String pid = entry.getKey();
                List<TubeExe> patientTubes = entry.getValue();
                String patientName = patientNameMap.getOrDefault(pid, "");

                for (TubeExe tube : patientTubes) {
                    totalTubes.incrementAndGet();

                    try {
                        // 按班次分组，取每班第一条有效记录
                        Map<String, TubeRecord> firstRecordByShift = selectFirstRecordByShift(tube);

                        for (Map.Entry<String, TubeRecord> shiftEntry : firstRecordByShift.entrySet()) {
                            String shiftType = shiftEntry.getKey();
                            TubeRecord record = shiftEntry.getValue();

                            try {
                                // 检查是否已同步
                                String historyKey = buildHistoryKey(tube.getId(), shiftType, record.getTime());
                                NurseRecordsHistory existingHistory = historyMap.get(historyKey);

                                // 拼接描述内容
                                String desc = buildDesc(tube, record, configMap);

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
                                        nurseRecord.setTime(record.getTime());
                                        nurseRecordsRepository.save(nurseRecord);

                                        existingHistory.setSyncContent(desc);
                                        existingHistory.setSyncTime(new Date());
                                        nurseRecordsHistoryRepository.save(existingHistory);

                                        updatedRecords.incrementAndGet();
                                        log.info("[TubeNursingSync] 更新护理记录 pid={} tubeType={} shift={}",
                                                pid, tube.getName(), shiftType);
                                    } else {
                                        // 护理记录被删除，重新创建
                                        NurseRecords newRecord = createNurseRecord(pid, patientName, tube, record, desc);
                                        NurseRecords saved = nurseRecordsRepository.insert(newRecord);

                                        existingHistory.setNurseRecordId(saved.getId());
                                        existingHistory.setSyncContent(desc);
                                        existingHistory.setSyncTime(new Date());
                                        nurseRecordsHistoryRepository.save(existingHistory);

                                        syncedRecords.incrementAndGet();
                                    }
                                } else {
                                    // 新增
                                    NurseRecords newRecord = createNurseRecord(pid, patientName, tube, record, desc);
                                    NurseRecords saved = nurseRecordsRepository.insert(newRecord);

                                    NurseRecordsHistory newHistory = new NurseRecordsHistory();
                                    newHistory.setPid(pid);
                                    newHistory.setTubeExeId(tube.getId());
                                    newHistory.setTubeType(tube.getName());
                                    newHistory.setShiftType(shiftType);
                                    newHistory.setTubeRecordTime(record.getTime());
                                    newHistory.setNurseRecordId(saved.getId());
                                    newHistory.setSyncTime(new Date());
                                    newHistory.setSyncContent(desc);
                                    nurseRecordsHistoryRepository.insert(newHistory);

                                    syncedRecords.incrementAndGet();
                                    log.info("[TubeNursingSync] 新增护理记录 pid={} tubeType={} shift={}",
                                            pid, tube.getName(), shiftType);
                                }
                            } catch (Exception e) {
                                log.error("[TubeNursingSync] 同步单条记录异常 pid={} tubeType={} shift={}",
                                        pid, tube.getName(), shiftType, e);
                                failedRecords.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        log.error("[TubeNursingSync] 处理管道异常 pid={} tubeType={}",
                                pid, tube.getName(), e);
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
     * 按班次分组，取每班第一条有效记录。
     */
    private Map<String, TubeRecord> selectFirstRecordByShift(TubeExe tube) {
        Map<String, TubeRecord> result = new HashMap<>();

        if (tube.getTubeRecordList() == null || tube.getTubeRecordList().isEmpty()) {
            return result;
        }

        for (TubeRecord record : tube.getTubeRecordList()) {
            // 只处理有效记录
            if (!Boolean.TRUE.equals(record.getValid())) {
                continue;
            }
            if (record.getTime() == null) {
                continue;
            }

            String shiftType = getShiftType(record.getTime());

            // 取每班时间最早的记录
            result.merge(shiftType, record, (existing, current) ->
                    current.getTime().before(existing.getTime()) ? current : existing);
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
    private String buildDesc(TubeExe tube, TubeRecord record, Map<String, ConfigTubeView> configMap) {
        StringBuilder sb = new StringBuilder();

        // 管道类型：置管时间
        sb.append(tube.getName()).append("：置管时间 ");
        if (tube.getStartTime() != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm");
            sb.append(sdf.format(tube.getStartTime()));
        }
        sb.append(";");

        // 操作人
        if (StringUtils.hasText(record.getRecordUserName())) {
            sb.append("操作人:").append(record.getRecordUserName()).append(";");
        }

        // 根据 configTubeView 配置拼接字段
        ConfigTubeView config = configMap.get(tube.getName());
        if (config != null && config.getTubeRecordFieldConfigList() != null) {
            for (TubeFieldConfig fieldConfig : config.getTubeRecordFieldConfigList()) {
                Object value = getFieldValue(record, fieldConfig.getField());
                if (value != null && StringUtils.hasText(value.toString())) {
                    sb.append(fieldConfig.getName()).append(":").append(value).append(";");
                }
            }
        }

        return sb.toString();
    }

    /**
     * 通过反射获取对象字段值。
     */
    private Object getFieldValue(Object obj, String fieldName) {
        try {
            Field field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(obj);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            log.debug("[TubeNursingSync] 无法获取字段 {} 的值", fieldName);
            return null;
        }
    }

    /**
     * 创建护理记录对象。
     */
    private NurseRecords createNurseRecord(String pid, String patientName,
                                            TubeExe tube, TubeRecord record, String desc) {
        NurseRecords nurseRecord = new NurseRecords();
        nurseRecord.setPid(pid);
        nurseRecord.setName(patientName);
        nurseRecord.setUsername(record.getRecordUserName());
        nurseRecord.setUserId(record.getRecordUser());
        nurseRecord.setDesc(desc);
        nurseRecord.setTime(record.getTime());
        nurseRecord.setCreateTime(new Date());
        nurseRecord.setValid(true);
        nurseRecord.setAutoSyn(true);
        nurseRecord.setUseTimes(0);
        nurseRecord.setDrugExeManualFlag(false);
        return nurseRecord;
    }
}
