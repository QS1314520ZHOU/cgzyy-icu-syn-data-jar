package com.digixmed.icu.viform.service;

import com.digixmed.icu.viform.config.OrderSyncProperties;
import com.digixmed.icu.viform.entity.Bedside;
import com.digixmed.icu.viform.entity.BedsideHistory;
import com.digixmed.icu.viform.entity.BedsideSyncLog;
import com.digixmed.icu.viform.entity.Order;
import com.digixmed.icu.viform.entity.Patient;
import com.digixmed.icu.viform.repository.datacenter.OrderRepository;
import com.digixmed.icu.viform.repository.smartcare.BedsideSyncLogRepository;
import com.digixmed.icu.viform.repository.smartcare.PatientRepository;
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
import java.util.stream.Collectors;

/**
 * 医嘱驱动 bedside 生成服务（param_biSiLiquid 鼻饲液）。
 *
 * <p>核心流程：</p>
 * <ol>
 *   <li>查在院患者 → 获取 mrn 列表</li>
 *   <li>查 DataCenter.VI_ICU_ZYYZ：mrn 匹配 + status 匹配 + orderName 命中关键词</li>
 *   <li>对每条命中的医嘱：幂等检查 → 生成 bedside 记录 → 记日志</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSyncService {

    private final PatientRepository patientRepository;
    private final OrderRepository orderRepository;
    private final BedsideSyncLogRepository syncLogRepository;
    private final OrderSyncProperties orderSyncProperties;

    /** DataCenter 库模板：若需要直接查 Order 可复用，此处通过 Repository 查询已经足够 */
    private final MongoTemplate smartCareMongoTemplate;

    /** 在院状态常量 */
    private static final String STATUS_ADMITTED = "admitted";

    /**
     * 执行一次医嘱同步（全量扫描），返回处理统计。
     *
     * @return Map 含 total/success/skip/fail 计数
     */
    public Map<String, Integer> sync() {
        log.info("[OrderSync] 开始医嘱同步...");

        int total = 0, success = 0, skip = 0, fail = 0;

        // 1. 查在院患者
        List<Patient> patients = patientRepository.findByStatus(STATUS_ADMITTED);
        if (CollectionUtils.isEmpty(patients)) {
            log.info("[OrderSync] 无在院患者，跳过");
            return Map.of("total", 0, "success", 0, "skip", 0, "fail", 0);
        }

        // 2. 提取有效 mrn 列表
        List<String> mrns = patients.stream()
                .map(Patient::getMrn)
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.toList());
        log.info("[OrderSync] 在院患者 MRN 数量: {}", mrns.size());

        if (mrns.isEmpty()) {
            log.info("[OrderSync] 无有效 MRN，跳过");
            return Map.of("total", 0, "success", 0, "skip", 0, "fail", 0);
        }

        // 3. 构建 mrn → patient 映射（一个 mrn 可能对应多个 patient？取第一个）
        Map<String, Patient> patientByMrn = new HashMap<>();
        for (Patient p : patients) {
            if (StringUtils.hasText(p.getMrn())) {
                patientByMrn.putIfAbsent(p.getMrn(), p);
            }
        }

        // 4. 查 DataCenter Order（按 mrn + status 批量）
        String orderStatus = orderSyncProperties.getOrderStatus();
        List<Order> orders = orderRepository.findByMrnInAndStatus(mrns, orderStatus);
        log.info("[OrderSync] 从 DataCenter 查询到 Order {} 条 (status={})", orders.size(), orderStatus);

        // 5. 按关键词过滤
        Set<String> keywords = new HashSet<>(orderSyncProperties.getOrderNameKeywords());
        // 统计每个关键词命中数，方便排查配置问题
        Map<String, Long> keywordHitCount = new LinkedHashMap<>();
        for (String kw : keywords) {
            long count = orders.stream()
                    .filter(o -> StringUtils.hasText(o.getOrderName()) && o.getOrderName().contains(kw))
                    .count();
            keywordHitCount.put(kw, count);
        }
        log.info("[OrderSync] 关键词命中分布: {}", keywordHitCount);

        List<Order> matchedOrders = orders.stream()
                .filter(o -> StringUtils.hasText(o.getOrderName()))
                .filter(o -> keywords.stream().anyMatch(kw -> o.getOrderName().contains(kw)))
                .collect(Collectors.toList());
        log.info("[OrderSync] 关键词命中 Order: {} 条 (总查询 {} 条, 命中率 {})",
                matchedOrders.size(), orders.size(),
                orders.isEmpty() ? "N/A" : String.format("%.1f%%", 100.0 * matchedOrders.size() / orders.size()));

        total = matchedOrders.size();

        // 6. 逐条处理
        String targetCode = orderSyncProperties.getTargetCode();
        String editUser = orderSyncProperties.getEditUser();

        for (Order order : matchedOrders) {
            try {
                Patient patient = patientByMrn.get(order.getMrn());
                if (patient == null) {
                    log.debug("[OrderSync] Order mrn={} 无对应在院患者，跳过", order.getMrn());
                    skip++;
                    continue;
                }

                // 幂等检查
                String sourceKey = buildSourceKey(order);
                Optional<BedsideSyncLog> existingLog = syncLogRepository.findBySourceKey(sourceKey);
                if (existingLog.isPresent()) {
                    log.debug("[OrderSync] 已同步 sourceKey={}, 跳过", sourceKey);
                    skip++;
                    continue;
                }

                // 生成 bedside 记录（upsert 模式，唯一键 pid+code+time）
                Query query = new Query(Criteria.where("pid").is(patient.getId())
                        .and("code").is(targetCode)
                        .and("time").is(order.getOrderTime()));

                BedsideHistory history = new BedsideHistory();
                history.setTime(new Date());
                history.setAccountId(editUser);
                history.setDesc("医嘱驱动同步-orderName=" + order.getOrderName());

                Update update = new Update()
                        .set("strVal", order.getOrderName())
                        .set("valid", true)
                        .set("synRemark", "医嘱同步生成")                   // 程序固定
                        .set("editUser", editUser)                          // 程序固定
                        .set("editTime", new Date())                        // 同步元数据
                        .set("history", Collections.singletonList(history))
                        .setOnInsert("_class", Bedside.BEDSIDE_CLASS);
                // 注意：不写 remark/不写 fVal，避免覆盖目标 bedside 已有字段

                smartCareMongoTemplate.upsert(query, update, Bedside.class);

                Bedside upserted = smartCareMongoTemplate.findOne(query, Bedside.class);
                String generatedId = upserted != null ? upserted.getId() : null;

                // 写同步日志
                BedsideSyncLog syncLog = new BedsideSyncLog();
                syncLog.setSyncType(BedsideSyncLog.TYPE_ORDER);
                syncLog.setCode(targetCode);
                syncLog.setPid(patient.getId());
                syncLog.setMrn(order.getMrn());
                syncLog.setSourceKey(sourceKey);
                syncLog.setTargetTimePoint(order.getOrderTime());
                syncLog.setGeneratedBedsideId(generatedId);
                syncLog.setResult(BedsideSyncLog.RESULT_SUCCESS);
                syncLog.setMessage("orderName=" + order.getOrderName() + " | mrn=" + order.getMrn());
                syncLog.setSyncTime(new Date());
                syncLogRepository.save(syncLog);

                log.info("[OrderSync] ✓ 生成 bedside pid={}, code={}, orderName=[{}], mrn={}, bedsideId={}",
                        patient.getId(), targetCode, order.getOrderName(), order.getMrn(), generatedId);
                success++;
            } catch (Exception e) {
                log.error("[OrderSync] 处理异常 orderId={}, mrn={}, orderName={}",
                        order.getId(), order.getMrn(), order.getOrderName(), e);
                try {
                    saveFailLog(order, targetCode, e.getMessage());
                } catch (Exception ignored) { /* 日志写入失败也不影响后续 */ }
                fail++;
            }
        }

        log.info("[OrderSync] 完成: total={}, success={}, skip={}, fail={}", total, success, skip, fail);
        return Map.of("total", total, "success", success, "skip", skip, "fail", fail);
    }

    // ==================== 工具方法 ====================

    /**
     * 构建医嘱同步唯一键。
     * 优先使用 order._id；若无则使用 mrn + "|" + orderName + "|" + orderTime 组合。
     */
    private String buildSourceKey(Order order) {
        if (StringUtils.hasText(order.getId())) {
            return "order:" + order.getId();
        }
        return String.format("order:%s|%s|%s",
                order.getMrn(),
                order.getOrderName(),
                order.getOrderTime() != null ? order.getOrderTime().getTime() : "null");
    }

    private void saveFailLog(Order order, String targetCode, String errorMsg) {
        BedsideSyncLog log = new BedsideSyncLog();
        log.setSyncType(BedsideSyncLog.TYPE_ORDER);
        log.setCode(targetCode);
        log.setMrn(order.getMrn());
        log.setSourceKey(buildSourceKey(order));
        log.setTargetTimePoint(order.getOrderTime());
        log.setResult(BedsideSyncLog.RESULT_FAIL);
        log.setMessage(errorMsg != null ? errorMsg.substring(0, Math.min(errorMsg.length(), 500)) : "未知异常");
        log.setSyncTime(new Date());
        syncLogRepository.save(log);
    }
}
