package com.digixmed.icu.viform.service;

import com.digixmed.icu.viform.common.ApiResult;
import com.digixmed.icu.viform.dto.BloodSugarPushDTO;
import com.digixmed.icu.viform.entity.Account;
import com.digixmed.icu.viform.entity.BloodSugar;
import com.digixmed.icu.viform.entity.Patient;
import com.digixmed.icu.viform.repository.smartcare.AccountRepository;
import com.digixmed.icu.viform.repository.smartcare.BloodSugarRepository;
import com.digixmed.icu.viform.repository.smartcare.PatientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * 血糖数据接收联调服务。
 *
 * <p>处理平台方推送的血糖数据：校验患者 → 分派保存/修改/删除 → 触发下游同步。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BloodSugarReceiveService {

    private final PatientRepository patientRepository;
    private final BloodSugarRepository bloodSugarRepository;
    private final AccountRepository accountRepository;
    private final SequenceGeneratorService sequenceGeneratorService;
    private final BloodSugarSyncService bloodSugarSyncService;

    /**
     * 处理推送的血糖数据。
     *
     * @param dto 血糖推送 DTO
     * @return ApiResult 处理结果
     */
    public ApiResult process(BloodSugarPushDTO dto) {
        // 1. 必填校验
        if (dto == null) {
            return ApiResult.fail("请求体为空", ApiResult.PARAM_INVALID);
        }
        String missing = validateRequired(dto);
        if (missing != null) {
            return ApiResult.fail("缺少必填字段: " + missing, ApiResult.PARAM_INVALID);
        }

        // 2. 查患者：根据 patNo → hisPid 匹配
        Patient patient = patientRepository.findByMrn(dto.getPatNo());
        if (patient == null) {
            log.info("[BloodSugar] 未找到对应患者 patNo={}", dto.getPatNo());
            return ApiResult.fail("未找到对应患者(patNo=" + dto.getPatNo() + ")",
                    ApiResult.PATIENT_NOT_FOUND);
        }
        log.info("[BloodSugar] 收到推送: patNo={}, patientId={}, name={}, operationType={}",
                dto.getPatNo(), patient.getId(), patient.getName(), dto.getOperationType());

        // 3. 根据操作类型分派
        int opType = dto.getOperationType();
        switch (opType) {
            case 1:
                return doSave(dto, patient);
            case 2:
                return doUpdate(dto, patient);
            case 3:
                return doDelete(dto, patient);
            default:
                log.warn("[BloodSugar] 未知操作类型 operationType={}", opType);
                return ApiResult.fail("未知操作类型: " + opType, ApiResult.UNKNOWN_OPERATION_TYPE);
        }
    }

    // ==================== 操作分派 ====================

    /**
     * 新增：自增主键 + 落库 + 触发同步。
     */
    private ApiResult doSave(BloodSugarPushDTO dto, Patient patient) {
        BloodSugar bs = buildBloodSugar(dto, patient);
        bs.setId(sequenceGeneratorService.nextSeq("bloodSugar"));
        bloodSugarRepository.save(bs);
        log.info("[BloodSugar] 保存成功 id={}, pid={}, patNo={}, result={}",
                bs.getId(), patient.getId(), dto.getPatNo(), dto.getBloGluVal());

        triggerSync();
        return ApiResult.success("血糖保存成功");
    }

    /**
     * 修改：按 (pid + time) 查已有记录，存在则更新字段 + 触发同步。
     */
    private ApiResult doUpdate(BloodSugarPushDTO dto, Patient patient) {
        Optional<BloodSugar> existing = bloodSugarRepository
                .findByPidAndTime(patient.getId(), dto.getBloGluDateTime());
        if (!existing.isPresent()) {
            log.info("[BloodSugar] 修改：未找到对应记录 pid={}, time={}, 静默跳过",
                    patient.getId(), dto.getBloGluDateTime());
            return ApiResult.success("无对应记录，跳过修改");
        }

        BloodSugar bs = existing.get();
        bs.setResult(dto.getBloGluVal());
        bs.setDetectionProject(dto.getTimePeriod());
        bs.setExaminer(dto.getOperatNurse());
        bs.setExaminerId(resolveExaminerId(dto.getOperatNurse()));
        bs.setRemarks(dto.getBloGluNote());
        bs.setSpecimenSource(""); // 平台未提供标本来源
        bloodSugarRepository.save(bs);
        log.info("[BloodSugar] 修改成功 id={}, pid={}, patNo={}, result={}",
                bs.getId(), patient.getId(), dto.getPatNo(), dto.getBloGluVal());

        triggerSync();
        return ApiResult.success("血糖修改成功");
    }

    /**
     * 删除（逻辑删除）：valid = false + 触发同步。
     */
    private ApiResult doDelete(BloodSugarPushDTO dto, Patient patient) {
        Optional<BloodSugar> existing = bloodSugarRepository
                .findByPidAndTime(patient.getId(), dto.getBloGluDateTime());
        if (!existing.isPresent()) {
            log.info("[BloodSugar] 删除：未找到对应记录 pid={}, time={}, 静默跳过",
                    patient.getId(), dto.getBloGluDateTime());
            return ApiResult.success("无对应记录，跳过删除");
        }

        BloodSugar bs = existing.get();
        bs.setValid(false);
        bloodSugarRepository.save(bs);
        log.info("[BloodSugar] 逻辑删除成功 id={}, pid={}, patNo={}",
                bs.getId(), patient.getId(), dto.getPatNo());

        triggerSync();
        return ApiResult.success("血糖删除成功");
    }

    // ==================== 内部工具方法 ====================

    /**
     * 将 DTO 映射为 BloodSugar 实体（不含主键）。
     */
    private BloodSugar buildBloodSugar(BloodSugarPushDTO dto, Patient patient) {
        BloodSugar bs = new BloodSugar();
        bs.setPid(patient.getId());
        bs.setTime(dto.getBloGluDateTime());
        bs.setResult(dto.getBloGluVal());
        bs.setDetectionProject(dto.getTimePeriod());
        bs.setExaminer(dto.getOperatNurse());
        bs.setExaminerId(resolveExaminerId(dto.getOperatNurse()));
        bs.setValid(true);
        bs.setSpecimenSource("");
        bs.setRemarks(dto.getBloGluNote());
        // 扩展字段（TODO: 确认 bloodSugar 实体是否已有下列字段，如无则需补充实体）
        bs.setBloGluNum(dto.getBloGluNum());
        bs.setTimePeriod(dto.getTimePeriod());
        bs.setTimePeriodType(dto.getTimePeriodType());
        bs.setPatName(dto.getPatName());
        bs.setPatNo(dto.getPatNo());
        bs.setHosInDate(dto.getHosInDate());
        bs.setWardCode(dto.getWardCode());
        bs.setWardName(dto.getWardName());
        bs.setPatBedNo(dto.getPatBedNo());
        bs.setOrgId(dto.getOrgId());
        return bs;
    }

    /**
     * 根据护士姓名从 account 集合匹配 examinerId。
     *
     * <p>重名时取第一条，匹配不到返回 null 并记日志。</p>
     */
    private String resolveExaminerId(String nurseName) {
        if (!StringUtils.hasText(nurseName)) {
            return null;
        }
        List<Account> accounts = accountRepository.findByName(nurseName);
        if (accounts.isEmpty()) {
            log.info("[BloodSugar] 未匹配到 examinerId: nurseName={}", nurseName);
            return null;
        }
        if (accounts.size() > 1) {
            log.info("[BloodSugar] 护士重名(取第一条): nurseName={}, 匹配到{}个",
                    nurseName, accounts.size());
        }
        return accounts.get(0).getId();
    }

    /**
     * 必填字段校验。
     *
     * @return 缺失的字段名，全部齐全返回 null
     */
    private String validateRequired(BloodSugarPushDTO dto) {
        if (!StringUtils.hasText(dto.getPatId())) return "patId";
        if (!StringUtils.hasText(dto.getPatName())) return "patName";
        if (!StringUtils.hasText(dto.getPatNo())) return "patNo";
        if (dto.getHosInDate() == null) return "hosInDate";
        if (!StringUtils.hasText(dto.getBloGluNum())) return "bloGluNum";
        if (!StringUtils.hasText(dto.getBloGluVal())) return "bloGluVal";
        if (dto.getBloGluDateTime() == null) return "bloGluDateTime";
        if (!StringUtils.hasText(dto.getTimePeriod())) return "timePeriod";
        if (!StringUtils.hasText(dto.getTimePeriodType())) return "timePeriodType";
        if (!StringUtils.hasText(dto.getOperatNurse())) return "operatNurse";
        if (!StringUtils.hasText(dto.getWardCode())) return "wardCode";
        if (!StringUtils.hasText(dto.getWardName())) return "wardName";
        if (!StringUtils.hasText(dto.getPatBedNo())) return "patBedNo";
        if (dto.getOperationType() == null) return "operationType";
        if (!StringUtils.hasText(dto.getOrgId())) return "orgId";
        return null;
    }

    /**
     * 触发既有血糖同步逻辑。
     *
     * <p>调用 {@link BloodSugarSyncService#sync()} 全量扫描同步。
     * 如需按患者/单条优化，可后续扩展方法签名。</p>
     */
    private void triggerSync() {
        try {
            java.util.Map<String, Integer> stats = bloodSugarSyncService.sync();
            log.info("[BloodSugar] 同步触发完成: {}", stats);
        } catch (Exception e) {
            log.error("[BloodSugar] 同步触发异常，不影响主流程", e);
        }
    }
}
