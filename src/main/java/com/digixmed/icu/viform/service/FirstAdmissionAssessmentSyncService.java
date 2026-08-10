package com.digixmed.icu.viform.service;

import com.digixmed.icu.viform.config.FirstAdmissionAssessmentSyncProperties;
import com.digixmed.icu.viform.config.FirstAdmissionAssessmentSyncProperties.FormOptionConfig;
import com.digixmed.icu.viform.entity.*;
import com.digixmed.icu.viform.repository.smartcare.BedsideRepository;
import com.digixmed.icu.viform.repository.smartcare.DFormDataRepository;
import com.digixmed.icu.viform.repository.smartcare.PatientRepository;
import com.digixmed.icu.viform.repository.smartcare.ScoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
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
import java.util.stream.Collectors;

/**
 * 首次入科评估同步服务。
 *
 * <p>读取在院患者的第一次有效 bedside 评估和跌倒/坠床 score 评估，
 * 比较后增量更新或创建入院/入科护理评估单 dFormData。</p>
 *
 * <p>核心策略：</p>
 * <ul>
 *   <li>表单不存在 + 有源数据 → 创建</li>
 *   <li>表单存在 → 逐字段比较，仅更新变化字段</li>
 *   <li>值一致 → 不写数据库</li>
 *   <li>源值为空 → 不覆盖</li>
 * </ul>
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

    /** 配置校验标记（只执行一次） */
    private volatile boolean configValidated = false;

    /** 在院状态常量 */
    private static final String STATUS_ADMITTED = "admitted";

    /** 表单有效状态 */
    private static final String FORM_VALID = "valid";

    /**
     * 基础目标字段（固定不变的字段）。
     * <p>选择类字段（依赖程度、跌倒评估方法）从配置动态获取，不再硬编码。</p>
     */
    private static final List<String> BASE_TARGET_FIELDS = Arrays.asList(
            "ttpf", "braden", "shzlnl", "dght",
            "morde", "morde2"
    );

    /**
     * 获取所有允许写入的目标字段（基础字段 + 配置的字段）。
     * <p>防止真实字段不在白名单中无法创建，也防止错误字段继续被写入。</p>
     */
    private List<String> getEffectiveTargetFields() {
        Set<String> fields = new LinkedHashSet<>(BASE_TARGET_FIELDS);
        if (properties.getFormOptionConfigs() != null) {
            for (FormOptionConfig config : properties.getFormOptionConfigs().values()) {
                if (config == null) continue;
                if (StringUtils.hasText(config.getDependencyField())) {
                    fields.add(config.getDependencyField());
                }
                if (StringUtils.hasText(config.getFallMethodField())) {
                    for (String f : config.getFallMethodFieldList()) {
                        fields.add(f);
                    }
                }
            }
        }
        return new ArrayList<>(fields);
    }

    /** 目标表单编码（从配置读取） */
    private List<String> getTargetFormCodes() {
        if (properties.getFormCodes() != null && !properties.getFormCodes().isEmpty()) {
            return properties.getFormCodes();
        }
        return Arrays.asList("zhuanruhulipinggudan", "ruyuanhulipinggudan");
    }

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
    }

    /** 同步结果统计 */
    public static class SyncResult {
        public final int totalPatients;
        public final int totalForms;
        public final int createdForms;
        public final int updatedForms;
        public final int unchangedForms;
        public final int noSourcePatients;
        public final int conflictForms;
        public final int failedForms;
        public final int createdFields;
        public final int updatedFields;

        public SyncResult(int totalPatients, int totalForms, int createdForms,
                          int updatedForms, int unchangedForms, int noSourcePatients,
                          int conflictForms, int failedForms, int createdFields,
                          int updatedFields) {
            this.totalPatients = totalPatients;
            this.totalForms = totalForms;
            this.createdForms = createdForms;
            this.updatedForms = updatedForms;
            this.unchangedForms = unchangedForms;
            this.noSourcePatients = noSourcePatients;
            this.conflictForms = conflictForms;
            this.failedForms = failedForms;
            this.createdFields = createdFields;
            this.updatedFields = updatedFields;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("totalPatients", totalPatients);
            m.put("totalForms", totalForms);
            m.put("createdForms", createdForms);
            m.put("updatedForms", updatedForms);
            m.put("unchangedForms", unchangedForms);
            m.put("noSourcePatients", noSourcePatients);
            m.put("conflictForms", conflictForms);
            m.put("failedForms", failedForms);
            m.put("createdFields", createdFields);
            m.put("updatedFields", updatedFields);
            return m;
        }
    }

    /**
     * 执行全量同步。
     */
    public SyncResult syncAllAdmittedPatients() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[FirstAssessmentSync] 上一轮任务尚未完成，跳过本次");
            return new SyncResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        AtomicInteger totalPatients = new AtomicInteger();
        AtomicInteger totalForms = new AtomicInteger();
        AtomicInteger createdForms = new AtomicInteger();
        AtomicInteger updatedForms = new AtomicInteger();
        AtomicInteger unchangedForms = new AtomicInteger();
        AtomicInteger noSourcePatients = new AtomicInteger();
        AtomicInteger conflictForms = new AtomicInteger();
        AtomicInteger failedForms = new AtomicInteger();
        AtomicInteger createdFields = new AtomicInteger();
        AtomicInteger updatedFields = new AtomicInteger();

        try {
            // 0. 配置校验（首次执行时）
            if (!configValidated) {
                properties.validate();
                configValidated = true;
            }

            // 1. 批量查询在院患者
            List<Patient> patients = patientRepository.findByStatus(STATUS_ADMITTED);
            patients = patients.stream()
                    .filter(p -> p.getIcuAdmissionTime() != null && StringUtils.hasText(p.getId()))
                    .collect(Collectors.toList());
            totalPatients.set(patients.size());
            log.info("[FirstAssessmentSync] 开始同步 admittedPatients={}", patients.size());

            if (patients.isEmpty()) {
                return new SyncResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
            }

            Map<String, Date> admissionTimes = new HashMap<>();
            for (Patient p : patients) {
                admissionTimes.put(p.getId(), p.getIcuAdmissionTime());
            }

            List<String> pids = patients.stream()
                    .map(Patient::getId)
                    .collect(Collectors.toList());

            // 2. 批量查询 bedside
            List<Bedside> bedsides = bedsideRepository.findByPidInAndCodeIn(pids, properties.getBedsideCodes());
            log.info("[FirstAssessmentSync] bedside 命中: {} 条", bedsides.size());

            Map<String, Map<String, Bedside>> firstBedsideMap =
                    sourceSelector.selectFirstBedsidePerPidAndCode(bedsides, admissionTimes);

            // 3. 批量查询 score
            List<Score> scores = scoreRepository.findByPidInAndScoreTypeAndValidTrue(
                    pids, properties.getScoreType());
            log.info("[FirstAssessmentSync] score 命中: {} 条", scores.size());

            Map<String, Score> firstScoreMap =
                    sourceSelector.selectFirstScorePerPid(scores, admissionTimes);

            // 4. 批量查询 dFormData（两个 formCode）
            List<DFormData> allForms = dFormDataRepository.findByPidInAndStatusAndFormCodeIn(
                    pids, FORM_VALID, getTargetFormCodes());
            log.info("[FirstAssessmentSync] dFormData 命中: {} 条", allForms.size());

            // 按 pid 分组
            Map<String, List<DFormData>> formsByPid = new HashMap<>();
            for (DFormData form : allForms) {
                formsByPid.computeIfAbsent(form.getPid(), k -> new ArrayList<>()).add(form);
            }

            // 5. 遍历每个在院患者（有源数据或有表单的）
            Set<String> pidsToProcess = new LinkedHashSet<>(formsByPid.keySet());
            pidsToProcess.addAll(firstBedsideMap.keySet());
            pidsToProcess.addAll(firstScoreMap.keySet());

            for (String pid : pidsToProcess) {
                Date admissionTime = admissionTimes.get(pid);
                if (admissionTime == null) continue;

                List<DFormData> patientForms = formsByPid.getOrDefault(pid, Collections.emptyList());

                // 6. 对两个 formCode 分别处理（lcpdf 可能按 formCode 不同，需分别构建）
                for (String formCode : getTargetFormCodes()) {
                    totalForms.incrementAndGet();
                    try {
                        // 构建候选值（传入 formCode 以获取正确的 lcpdf 编码）
                        Map<String, Object> candidateValues = sourceSelector.buildCandidateValues(
                                pid, firstBedsideMap, firstScoreMap, formCode);

                        Optional<DFormData> existing = patientForms.stream()
                                .filter(f -> formCode.equals(f.getFormCode()))
                                .findFirst();

                        if (existing.isPresent()) {
                            // 已存在 → 比较后更新
                            SyncStatus status = syncExistingForm(
                                    existing.get(), candidateValues);
                            switch (status) {
                                case UPDATED: updatedForms.incrementAndGet(); break;
                                case UNCHANGED: unchangedForms.incrementAndGet(); break;
                                case CONFLICT: conflictForms.incrementAndGet(); break;
                                default: break;
                            }
                        } else {
                            // 不存在 → 创建
                            SyncStatus status = createFormIfSourceExists(
                                    pid, formCode, candidateValues);
                            switch (status) {
                                case CREATED: createdForms.incrementAndGet(); break;
                                case NO_SOURCE: noSourcePatients.incrementAndGet(); break;
                                default: break;
                            }
                        }
                    } catch (Exception e) {
                        log.error("[FirstAssessmentSync] 异常 pid={}, formCode={}", pid, formCode, e);
                        failedForms.incrementAndGet();
                    }
                }
            }

            log.info("[FirstAssessmentSync] 完成 createdForms={} updatedForms={} unchangedForms={} "
                            + "noSource={} conflicts={} failed={}",
                    createdForms.get(), updatedForms.get(), unchangedForms.get(),
                    noSourcePatients.get(), conflictForms.get(), failedForms.get());

            return new SyncResult(totalPatients.get(), totalForms.get(),
                    createdForms.get(), updatedForms.get(), unchangedForms.get(),
                    noSourcePatients.get(), conflictForms.get(), failedForms.get(),
                    createdFields.get(), updatedFields.get());

        } catch (Exception e) {
            log.error("[FirstAssessmentSync] 同步异常", e);
            failedForms.incrementAndGet();
            return new SyncResult(totalPatients.get(), totalForms.get(),
                    createdForms.get(), updatedForms.get(), unchangedForms.get(),
                    noSourcePatients.get(), conflictForms.get(), failedForms.get(),
                    createdFields.get(), updatedFields.get());
        } finally {
            running.set(false);
        }
    }

    // ==================== 创建表单 ====================

    /**
     * 表单不存在时，有源数据则创建。
     */
    private SyncStatus createFormIfSourceExists(String pid, String formCode,
                                                 Map<String, Object> candidateValues) {
        if (candidateValues.isEmpty()) {
            return SyncStatus.NO_SOURCE;
        }

        // 构建 fieldDataList
        List<Document> mongoFieldDataList = buildMongoFieldDataList(candidateValues);
        if (mongoFieldDataList.isEmpty()) {
            log.info("[FirstAssessmentSync] pid={} formCode={} no source values, skip creation",
                    pid, formCode);
            return SyncStatus.NO_SOURCE;
        }

        log.info("[FirstAssessmentSync] pid={} formCode={} not found, creating", pid, formCode);

        // 使用原生 Document 创建，确保 _class 正确
        Document document = new Document();
        document.put("pid", pid);
        document.put("formCode", formCode);
        document.put("status", FORM_VALID);
        document.put("fieldDataList", mongoFieldDataList);
        document.put("_class", DFormData.D_FORM_DATA_CLASS);

        smartCareMongoTemplate.getCollection("dFormData").insertOne(document);

        String formId = document.getObjectId("_id").toHexString();
        log.info("[FirstAssessmentSync] pid={} formCode={} created formId={} fields={}",
                pid, formCode, formId, mongoFieldDataList.size());

        return SyncStatus.CREATED;
    }

    /**
     * 将 candidateValues 转换为 MongoDB Document 列表。
     * <p>只包含白名单字段，跳过空值。</p>
     */
    private List<Document> buildMongoFieldDataList(Map<String, Object> candidateValues) {
        List<Document> result = new ArrayList<>();
        for (String field : getEffectiveTargetFields()) {
            Object value = candidateValues.get(field);
            if (isEmptySourceValue(value)) {
                continue;
            }
            Object normalized = normalizeForCreate(field, value);
            if (isEmptySourceValue(normalized)) {
                continue;
            }
            Document fieldDoc = new Document();
            fieldDoc.put("field", field);
            fieldDoc.put("value", normalizeValueForMongo(normalized));
            result.add(fieldDoc);
        }
        return result;
    }

    /**
     * 创建时的值规范化。
     */
    private Object normalizeForCreate(String field, Object value) {
        if (value == null) return null;
        // List<String> 直接保留
        if (value instanceof List) {
            return value;
        }
        // 数值字段规范化为字符串
        if ("morde".equals(field)) {
            String s = normalizeScoreTotal(value);
            return StringUtils.hasText(s) ? s : null;
        }
        // 普通字符串 trim
        if (value instanceof String) {
            String s = ((String) value).trim();
            return s.isEmpty() ? null : s;
        }
        return value;
    }

    private String normalizeScoreTotal(Object total) {
        if (total instanceof Number) {
            int v = ((Number) total).intValue();
            return String.valueOf(v);
        }
        String s = total.toString().trim();
        try {
            int v = (int) Double.parseDouble(s);
            return String.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 值写入 MongoDB 前的规范化。
     */
    private Object normalizeValueForMongo(Object value) {
        if (value instanceof List) {
            // List<String> 元素 trim
            List<?> raw = (List<?>) value;
            List<String> result = new ArrayList<>();
            for (Object item : raw) {
                if (item != null) {
                    String s = String.valueOf(item).trim();
                    if (!s.isEmpty()) result.add(s);
                }
            }
            return result;
        }
        return value;
    }

    // ==================== 更新已有表单 ====================

    private enum SyncStatus { CREATED, UPDATED, UNCHANGED, NO_SOURCE, CONFLICT, FAILED }

    /**
     * 对已有表单执行增量同步（逐字段比较，仅更新变化字段）。
     */
    private SyncStatus syncExistingForm(DFormData form, Map<String, Object> candidateValues) {
        String pid = form.getPid();
        String formCode = form.getFormCode();

        if (CollectionUtils.isEmpty(candidateValues)) {
            return SyncStatus.UNCHANGED;
        }

        List<FieldChange> changes = new ArrayList<>();

        for (String field : getEffectiveTargetFields()) {
            Object sourceValue = candidateValues.get(field);
            if (isEmptySourceValue(sourceValue)) {
                continue;
            }

            Object oldValue = findFieldValue(form.getFieldDataList(), field);
            Object normalizedNew = comparator.normalizeForWrite(field, oldValue, sourceValue);

            if (comparator.valuesEqual(field, oldValue, normalizedNew)) {
                log.debug("[FirstAssessmentSync] pid={} formCode={} field={} value unchanged, skip",
                        pid, formCode, field);
                continue;
            }

            changes.add(new FieldChange(field, oldValue, normalizedNew));
        }

        if (changes.isEmpty()) {
            log.debug("[FirstAssessmentSync] pid={} formCode={} all values unchanged, skip",
                    pid, formCode);
            return SyncStatus.UNCHANGED;
        }

        log.info("[FirstAssessmentSync] pid={} formCode={} changedFields={}",
                pid, formCode, changes.stream().map(c -> c.field).collect(Collectors.joining(",")));

        return applyFieldUpdates(form, changes);
    }

    /**
     * 精准更新变化字段（字段级条件更新）。
     */
    private SyncStatus applyFieldUpdates(DFormData form, List<FieldChange> changes) {
        String formId = form.getId();
        boolean anyConflict = false;

        for (FieldChange change : changes) {
            boolean fieldExists = fieldExistsInList(form.getFieldDataList(), change.field);

            if (fieldExists) {
                Query query = new Query(Criteria.where("_id").is(formId)
                        .and("status").is(FORM_VALID)
                        .and("fieldDataList").elemMatch(
                                Criteria.where("field").is(change.field)));

                Update update = new Update();
                update.set("fieldDataList.$.value", normalizeValueForMongo(change.newValue));

                var result = smartCareMongoTemplate.updateFirst(query, update, DFormData.class);

                if (result.getModifiedCount() == 0) {
                    DFormData reloaded = smartCareMongoTemplate.findOne(
                            new Query(Criteria.where("_id").is(formId)), DFormData.class);
                    if (reloaded != null) {
                        Object currentValue = findFieldValue(reloaded.getFieldDataList(), change.field);
                        if (comparator.valuesEqual(change.field, currentValue, change.newValue)) {
                            log.debug("[FirstAssessmentSync] pid={} field={} already updated",
                                    form.getPid(), change.field);
                            continue;
                        }
                    }
                    log.warn("[FirstAssessmentSync] pid={} formCode={} field={} concurrent modification",
                            form.getPid(), form.getFormCode(), change.field);
                    anyConflict = true;
                }
            } else {
                // 追加新字段
                Query query = new Query(Criteria.where("_id").is(formId)
                        .and("status").is(FORM_VALID)
                        .and("fieldDataList.field").ne(change.field));

                Document fieldDoc = new Document();
                fieldDoc.put("field", change.field);
                fieldDoc.put("value", normalizeValueForMongo(change.newValue));

                Update update = new Update().push("fieldDataList", fieldDoc);

                var result = smartCareMongoTemplate.updateFirst(query, update, DFormData.class);
                if (result.getModifiedCount() == 0) {
                    log.debug("[FirstAssessmentSync] pid={} field={} may already exist, skip",
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

    private boolean isEmptySourceValue(Object value) {
        if (value == null) return true;
        if (value instanceof String) return !StringUtils.hasText((String) value);
        if (value instanceof List) return ((List<?>) value).isEmpty();
        return false;
    }
}
