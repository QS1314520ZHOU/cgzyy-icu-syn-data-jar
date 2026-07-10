package com.digixmed.icu.viform.service;

import com.digixmed.icu.viform.entity.Bedside;
import com.digixmed.icu.viform.entity.Patient;
import com.digixmed.icu.viform.repository.smartcare.BedsideRepository;
import com.digixmed.icu.viform.repository.smartcare.PatientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 核心业务：读取在院患者（patient.status = admitted），
 * 按 patient.id = bedside.pid 关联其 bedside 记录，
 * 再根据 bedside.code 分发到对应的处理逻辑。
 *
 * <p>具体的业务处理逻辑由 {@link BedsideCodeHandler} 实现，后续可按 code 逐一补全。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdmittedPatientBedsideService {

    /** 在院状态常量。 */
    public static final String STATUS_ADMITTED = "admitted";

    private final PatientRepository patientRepository;
    private final BedsideRepository bedsideRepository;
    private final BedsideCodeDispatcher dispatcher;

    /**
     * 关注的 bedside code 列表（来自配置 syn.bedside-codes，逗号分隔）。
     * 为空时表示不过滤，处理患者的全部 bedside 记录。
     */
    @Value("${syn.bedside-codes:}")
    private List<String> bedsideCodes;

    /**
     * 执行一次完整处理流程，返回处理的 bedside 记录数。
     */
    public int process() {
        List<Patient> admittedPatients = patientRepository.findByStatus(STATUS_ADMITTED);
        log.info("[SynData] 在院患者数量: {}", admittedPatients.size());
        if (CollectionUtils.isEmpty(admittedPatients)) {
            return 0;
        }

        // patient.id 列表
        List<String> patientIds = admittedPatients.stream()
                .map(Patient::getId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());

        // 一次性批量拉取 bedside，避免 N+1 查询
        List<Bedside> bedsides;
        if (CollectionUtils.isEmpty(bedsideCodes)) {
            bedsides = bedsideRepository.findByPidIn(patientIds);
        } else {
            bedsides = bedsideRepository.findByPidInAndCodeIn(patientIds, bedsideCodes);
        }
        log.info("[SynData] 命中 bedside 记录数量: {} (codes={})", bedsides.size(),
                CollectionUtils.isEmpty(bedsideCodes) ? "ALL" : bedsideCodes);

        // 按 pid 分组，便于 handler 拿到同一患者的上下文
        Map<String, Patient> patientById = admittedPatients.stream()
                .collect(Collectors.toMap(Patient::getId, p -> p, (a, b) -> a));
        Map<String, List<Bedside>> bedsidesByPid = bedsides.stream()
                .filter(b -> StringUtils.hasText(b.getPid()))
                .collect(Collectors.groupingBy(Bedside::getPid));

        int handled = 0;
        for (Map.Entry<String, List<Bedside>> entry : bedsidesByPid.entrySet()) {
            Patient patient = patientById.get(entry.getKey());
            if (patient == null) {
                continue;
            }
            for (Bedside bedside : entry.getValue()) {
                try {
                    dispatcher.dispatch(patient, bedside);
                    handled++;
                } catch (Exception ex) {
                    log.error("[SynData] 处理 bedside 失败 pid={}, code={}, id={}",
                            bedside.getPid(), bedside.getCode(), bedside.getId(), ex);
                }
            }
        }
        log.info("[SynData] 处理完成，成功处理 bedside 记录: {}", handled);
        return handled;
    }

    /**
     * 查询单个在院患者的 bedside 记录（按需过滤 code），便于调试/接口调用。
     */
    public List<Bedside> findBedsidesForPatient(String patientId) {
        if (!StringUtils.hasText(patientId)) {
            return Collections.emptyList();
        }
        if (CollectionUtils.isEmpty(bedsideCodes)) {
            return bedsideRepository.findByPid(patientId);
        }
        List<Bedside> result = new ArrayList<>();
        for (String code : bedsideCodes) {
            result.addAll(bedsideRepository.findByPidAndCode(patientId, code));
        }
        return result;
    }
}
