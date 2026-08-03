package com.digixmed.icu.viform.service;

import com.digixmed.icu.viform.config.FirstAdmissionAssessmentSyncProperties;
import com.digixmed.icu.viform.entity.*;
import com.digixmed.icu.viform.repository.smartcare.BedsideRepository;
import com.digixmed.icu.viform.repository.smartcare.DFormDataRepository;
import com.digixmed.icu.viform.repository.smartcare.PatientRepository;
import com.digixmed.icu.viform.repository.smartcare.ScoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 首次入科评估同步服务。
 *
 * <p>读取在院患者的第一次有效 bedside 评估和跌倒/坠床 score 评估，
 * 比较后增量更新到入院/入科护理评估单 dFormData。</p>
 *
 * <p>核心策略：逐字段比较，仅更新变化字段，源值为空不覆盖。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FirstAdmissionAssessmentSyncService {

    private final PatientRepository patientRepository;
    private final BedsideRepository bedsideRepository;
    private final ScoreRepository scoreRepository;
    private final DFormDataRepository dFormDataRepository;
    private final FirstAdmissionAssessmentSyncProperties properties;
    private final FirstAssessmentSourceSelector sourceSelector;
    private final DFormFieldValueComparator comparator;
    private final MongoTemplate smartCareMongoTemplate;

    /** 防重入锁 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 在院状态常量 */
    private static final String STATUS_ADMITTED = "admitted";

    /** 表单有效状态 */
    private static final String FORM_VALID = "valid";

    /** 目标字段白名单 */
    private static final Set<String> TARGET_FIELDS = new LinkedHashSet<>(Arrays.asList(
            "ttt", "braden", "branden2", "barthel", "barthel2",
            "dght", "dght2", "lcpdf", "mpff", "morde", "morde2"
    ));

    /** 变更记录 */
    public static class FieldChange {
        public final String field;
        public final Object oldValue;
        public final Object newValue;

        public FieldChange(String field, Object oldValue, Object newValue) {
            this.field = field;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }

        @Override
        public String toString() {
            return field + "(" + oldValue + " → " + newValue + ")";
        }
    }

    /** 同步结果统计 */
    public static class SyncResult {
        public final int totalPatients;
        public final int matchedPatients;
        public final int totalForms;
        public final int updatedForms;
        public final int unchangedForms;
        public final int noSourcePatients;
        public final int noFormPatients;
        public final int conflictForms;
        public final int failedForms;
        public final int updatedFields;

        public SyncResult(int totalPatients, int matchedPatients, int totalForms,
                          int updatedForms, int unchangedForms, int noSourcePatients,
                          int noFormPatients, int conflictForms, int failedForms,
                          int updatedFields) {
            this.totalPatients = totalPatients;
            this.matchedPatients = matchedPatients;
            this.totalForms = totalForms;
            this.updatedForms = updatedForms;
            this.unchangedForms = unchangedForms;
            this.noSourcePatients = noSourcePatients;
            this.noFormPatients = noFormPatients;
            this.conflictForms = conflictForms;
            this.failedForms = failedForms;
            this.updatedFields = updatedFields;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("totalPatients", totalPatients);
            m.put("matchedPatients", matchedPatients);
            m.put("totalForms", totalForms);
            m.put("updatedForms", updatedForms);
            m.put("unchangedForms", unchangedForms);
            m.put("noSourcePatients", noSourcePatients);
            m.put("noFormPatients", noFormPatients);
            m.put("conflictForms", conflictForms);
            m.put("failedForms", failedForms);
            m.put("updatedFields", updatedFields);
            return m;
        }
    }

    /**
     * 执行全量同步（在院患者的首次入科评估同步）。
     *
     * @return 同步结果
     */
    public SyncResult syncAllAdmittedPatients() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[FirstAssessmentSync] 上一轮任务尚未完成，跳过本次");
            return new SyncResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        AtomicInteger totalPatients = new AtomicInteger();
        AtomicInteger matchedPatients = new AtomicInteger();
        AtomicInteger totalForms = new AtomicInteger();
        AtomicInteger updatedForms = new AtomicInteger();
        AtomicInteger unchangedForms = new AtomicInteger();
        AtomicInteger noSourcePatients = new AtomicInteger();
        AtomicInteger noFormPatients = new AtomicInteger();
        AtomicInteger conflictForms = new AtomicInteger();
        AtomicInteger failedForms = new AtomicInteger();
        AtomicInteger updatedFields = new AtomicInteger();

        try {
            // 1. 批量查询在院患者
            List<Patient> patients = patientRepository.findByStatus(STATUS_ADMITTED);
            patients = patients.stream()
                    .filter(p -> p.getIcuAdmissionTime() != null && StringUtils.hasText(p.getId()))
                    .collect(java.util.stream.Collectors.toList());
            totalPatients.set(patients.size());
            log.info("[FirstAssessmentSync] 开始同步 admittedPatients={}", patients.size());

            if (patients.isEmpty()) {
                return new SyncResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
            }

            // 构建 pid → icuAdmissionTime 映射
            Map<String, Date> admissionTimes = new HashMap<>();
            for (Patient p : patients) {
                admissionTimes.put(p.getId(), p.getIcuAdmissionTime());
            }

            List<String> pids = patients.stream()
                    .map(Patient::getId)
                    .collect(java.util.stream.Collectors.toList());

            // 2. 批量查询 bedside
            List<Bedside> bedsides = bedsideRepository.findByPidInAndCodeIn(pids, properties.getBedsideCodes());
            log.info("[FirstAssessmentSync] bedside 命中: {} 条", bedsides.size());

            Map<String, Map<String, Bedside>> firstBedsideMap =
                    sourceSelector.selectFirstBedsidePerPidAndCode(bedsides, admissionTimes);

            matchedPatients.set(firstBedsideMap.size());

            // 3. 批量查询 score
            List<Score> scores = scoreRepository.findByPidInAndScoreTypeAndValidTrue(
                    pids, properties.getScoreType());
            log.info("[FirstAssessmentSync] score 命中: {} 条", scores.size());

            Map<String, Score> firstScoreMap =
                    sourceSelector.selectFirstScorePerPid(scores, admissionTimes);

            // 4. 批量查询 dFormData
            List<DFormData> allForms = dFormDataRepository.findByPidInAndStatusAndFormCodeIn(
                    pids, FORM_VALID, properties.getFormCodes());
            log.info("[FirstAssessmentSync] dFormData 命中: {} 条", allForms.size());

            // 按 pid 分组
            Map<String, List<DFormData>> formsByPid = new HashMap<>();
            for (DFormData form : allForms) {
                formsByPid.computeIfAbsent(form.getPid(), k -> new ArrayList<>()).add(form);
            }

            // 5. 遍历每个有表单的患者
            for (Map.Entry<String, List<DFormData>> formEntry : formsByPid.entrySet()) {
                String pid = formEntry.getKey();
                Date admissionTime = admissionTimes.get(pid);

                // 6. 构建候选值
                Map<String, Object> candidateValues = sourceSelector.buildCandidateValues(
                        pid, firstBedsideMap, firstScoreMap);

                boolean hasAnySource = !candidateValues.isEmpty();
                if (!hasAnySource) {
                    noSourcePatients.incrementAndGet();
                    log.debug("[FirstAssessmentSync] pid={} 无有效源数据，跳过", pid);
                    continue;
                }

                // 7. 对每个表单逐字段比较
                for (DFormData form : formEntry.getValue()) {
                    totalForms.incrementAndGet();
                    try {
                        SyncStatus status = syncOneForm(form, candidateValues, admissionTime);
                        switch (status) {
                            case UPDATED: updatedForms.incrementAndGet(); break;
                            case UNCHANGED: unchangedForms.incrementAndGet(); break;
                            case CONFLICT: conflictForms.incrementAndGet(); break;
                            case NO_SOURCE: noSourcePatients.incrementAndGet(); break;
                            default: break;
                        }
                    } catch (Exception e) {
                        log.error("[FirstAssessmentSync] 异常 pid={}, formId={}", pid, form.getId(), e);
                        failedForms.incrementAndGet();
                    }
                }
            }

            int updated = updatedForms.get();
            log.info("[FirstAssessmentSync] 完成 updatedForms={} unchangedForms={} noSource={} noForm={} conflicts={} failed={}",
                    updated, unchangedForms.get(), noSourcePatients.get(), noFormPatients.get(),
                    conflictForms.get(), failedForms.get());

            return new SyncResult(totalPatients.get(), matchedPatients.get(), totalForms.get(),
                    updatedForms.get(), unchangedForms.get(), noSourcePatients.get(),
                    noFormPatients.get(), conflictForms.get(), failedForms.get(),
                    updatedFields.get());

        } catch (Exception e) {
            log.error("[FirstAssessmentSync] 同步异常", e);
            failedForms.incrementAndGet();
            return new SyncResult(totalPatients.get(), matchedPatients.get(), totalForms.get(),
                    updatedForms.get(), unchangedForms.get(), noSourcePatients.get(),
                    noFormPatients.get(), conflictForms.get(), failedForms.get(),
                    updatedFields.get());
        } finally {
            running.set(false);
        }
    }

    // ==================== 单表单同步 ====================

    private enum SyncStatus { UPDATED, UNCHANGED, NO_SOURCE, NO_FORM, CONFLICT, FAILED }

    /**
     * 对单个表单执行增量同步。
     */
    private SyncStatus syncOneForm(DFormData form, Map<String, Object> candidateValues,
                                   Date admissionTime) {
        String pid = form.getPid();
        String formCode = form.getFormCode();

        if (CollectionUtils.isEmpty(candidateValues)) {
            return SyncStatus.NO_SOURCE;
        }

        // 构建变更集
        List<FieldChange> changes = new ArrayList<>();

        for (Map.Entry<String, Object> entry : candidateValues.entrySet()) {
            String field = entry.getKey();
            Object sourceValue = entry.getValue();

            // 白名单检查
            if (!TARGET_FIELDS.contains(field)) {
                log.warn("[FirstAssessmentSync] pid={} formCode={} 非白名单字段={}，忽略", pid, formCode, field);
                continue;
            }

            // 空源值不处理
            if (sourceValue == null || (sourceValue instanceof String && !StringUtils.hasText((String) sourceValue))) {
                continue;
            }

            // 读目标当前值
            Object oldValue = findFieldValue(form.getFieldDataList(), field);

            // 规范化新值
            Object normalizedNew = comparator.normalizeForWrite(field, oldValue, sourceValue);

            // 值比较
            if (comparator.valuesEqual(field, oldValue, normalizedNew)) {
                log.debug("[FirstAssessmentSync] pid={} formCode={} field={} value unchanged, skip",
                        pid, formCode, field);
                continue;
            }

            changes.add(new FieldChange(field, oldValue, normalizedNew));
        }

        // 所有字段值一致，不执行数据库写入
        if (changes.isEmpty()) {
            log.debug("[FirstAssessmentSync] pid={} formCode={} all values unchanged, skip database update",
                    pid, formCode);
            return SyncStatus.UNCHANGED;
        }

        // 精准更新变化字段（字段级条件更新）
        log.info("[FirstAssessmentSync] pid={} formCode={} changedFields={}",
                pid, formCode, changes);

        return applyFieldUpdates(form, changes);
    }

    /**
     * 对变化字段执行精准更新（字段级条件更新）。
     */
    private SyncStatus applyFieldUpdates(DFormData form, List<FieldChange> changes) {
        String formId = form.getId();
        boolean anyConflict = false;

        for (FieldChange change : changes) {
            boolean fieldExists = fieldExistsInList(form.getFieldDataList(), change.field);

            if (fieldExists) {
                // 字段已存在 → 位置更新（带条件：当前值仍为 oldValue）
                Query query = new Query(Criteria.where("_id").is(formId)
                        .and("status").is(FORM_VALID)
                        .and("fieldDataList").elemMatch(
                                Criteria.where("field").is(change.field)
                                        .and("value").is(change.oldValue)));

                Update update = new Update();
                update.set("fieldDataList.$.value", change.newValue);

                // 条件更新
                var result = smartCareMongoTemplate.updateFirst(query, update, DFormData.class);

                if (result.getModifiedCount() == 0) {
                    // 检查是否已等于目标值
                    DFormData reloaded = smartCareMongoTemplate.findOne(
                            new Query(Criteria.where("_id").is(formId)), DFormData.class);
                    if (reloaded != null) {
                        Object currentValue = findFieldValue(reloaded.getFieldDataList(), change.field);
                        if (comparator.valuesEqual(change.field, currentValue, change.newValue)) {
                            log.debug("[FirstAssessmentSync] pid={} field={} already updated by another thread",
                                    form.getPid(), change.field);
                            // ALREADY_UPDATED，不计为冲突
                            continue;
                        }
                    }
                    // 真正的并发冲突
                    log.warn("[FirstAssessmentSync] pid={} formCode={} field={} concurrent modification detected",
                            form.getPid(), form.getFormCode(), change.field);
                    anyConflict = true;
                }
            } else {
                // 字段不存在 → 追加（带条件：确认不存在该 field）
                Query query = new Query(Criteria.where("_id").is(formId)
                        .and("status").is(FORM_VALID)
                        .and("fieldDataList.field").ne(change.field));

                Update update = new Update().push("fieldDataList",
                        new LinkedHashMap<String, Object>() {{
                            put("field", change.field);
                            put("value", change.newValue);
                        }});

                var result = smartCareMongoTemplate.updateFirst(query, update, DFormData.class);
                if (result.getModifiedCount() == 0) {
                    log.debug("[FirstAssessmentSync] pid={} field={} field may already exist, skip append",
                            form.getPid(), change.field);
                }
            }
        }

        return anyConflict ? SyncStatus.CONFLICT : SyncStatus.UPDATED;
    }

    // ==================== 工具方法 ====================

    private Object findFieldValue(List<DFormFieldData> fieldDataList, String field) {
        if (fieldDataList == null) return null;
        for (DFormFieldData fd : fieldDataList) {
            if (field.equals(fd.getField())) {
                return fd.getValue();
            }
        }
        return null;
    }

    private boolean fieldExistsInList(List<DFormFieldData> fieldDataList, String field) {
        return findFieldValue(fieldDataList, field) != null;
    }
}
