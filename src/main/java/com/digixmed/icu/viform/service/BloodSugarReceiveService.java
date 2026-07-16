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
import java.util.Optional;

/**
 * 血糖数据接收联调服务。
 *
 * <p>处理平台方推送的血糖数据：校验患者 → 防重复查询 → 分派新增/修改/删除 → 触发下游同步。
 * 严禁 DTO 字段写入 bloodSugar 集合，只用显式映射的固定实体字段。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BloodSugarReceiveService {

    private final PatientRepository patientRepository;
    private final BloodSugarRepository bloodSugarRepository;
    private final AccountRepository accountRepository;
    private final BloodSugarSyncService bloodSugarSyncService;

    /** operationType 常量 */
    private static final int OP_SAVE = 1;
    private static final int OP_UPDATE = 2;
    private static final int OP_DELETE = 3;

    /**
     * 处理推送的血糖数据。
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

        // 2. 查患者：patNo → patient.mrn
        Patient patient = patientRepository.findByMrn(dto.getPatNo());
        if (patient == null) {
            log.info("[BloodSugar] 未找到对应患者 patNo={}", dto.getPatNo());
            return ApiResult.fail("未找到对应患者(patNo=" + dto.getPatNo() + ")",
                    ApiResult.PATIENT_NOT_FOUND);
        }
        log.info("[BloodSugar] 收到推送: patNo={}, patientId={}, name={}, operationType={}",
                dto.getPatNo(), patient.getId(), patient.getName(), dto.getOperationType());

        // 3. 防重复：按 (pid + time) 查 valid=true 的已有记录
        Optional<BloodSugar> existingOpt = bloodSugarRepository
                .findFirstByPidAndTimeAndValidTrue(patient.getId(), dto.getBloGluDateTime());
        boolean exists = existingOpt.isPresent();
        BloodSugar existing = existingOpt.orElse(null);

        int opType = dto.getOperationType();

        // 4. 防重复矩阵分派
        if (opType == OP_SAVE) {
            if (exists) {
                return doUpdate(existing, dto, patient, "保存→已有记录，更新");
            } else {
                return doInsert(dto, patient);
            }
        } else if (opType == OP_UPDATE) {
            if (exists) {
                return doUpdate(existing, dto, patient, "修改→更新已有记录");
            } else {
                return doInsert(dto, patient);
            }
        } else if (opType == OP_DELETE) {
            if (exists) {
                return doDelete(existing, dto, patient);
            } else {
                log.info("[BloodSugar] 删除：无对应记录 pid={}, time={}, 静默跳过",
                        patient.getId(), dto.getBloGluDateTime());
                return ApiResult.success("无对应记录，跳过删除");
            }
        } else {
            log.warn("[BloodSugar] 未知操作类型 operationType={}", opType);
            return ApiResult.fail("未知操作类型: " + opType, ApiResult.UNKNOWN_OPERATION_TYPE);
        }
    }

    // ==================== 操作实现 ====================

    /** 新增一条 bloodSugar（显式字段映射）。 */
    private ApiResult doInsert(BloodSugarPushDTO dto, Patient patient) {
        BloodSugar bs = new BloodSugar();
        // 只映射固定的 10 个实体字段，禁止 DTO 整体拷贝
        bs.setPid(patient.getId());
        bs.setTime(dto.getBloGluDateTime());
        bs.setResult(dto.getBloGluVal());
        bs.setDetectionProject(dto.getTimePeriod());
        bs.setExaminer(dto.getOperatNurse());
        bs.setExaminerId(resolveExaminerId(dto.getOperatNurse()));
        bs.setValid(true);
        bs.setSpecimenSource("");   // TODO：确认业务默认值
        bs.setDeviceCode("");       // TODO：确认业务默认值
        bs.setRemarks(dto.getBloGluNote());
        bloodSugarRepository.save(bs);
        log.info("[BloodSugar] 新增成功 id={}, pid={}, patNo={}, result={}",
                bs.getId(), patient.getId(), dto.getPatNo(), dto.getBloGluVal());
        triggerSync();
        return ApiResult.success("血糖保存成功");
    }

    /** 更新已有记录（显式字段映射）。 */
    private ApiResult doUpdate(BloodSugar existing, BloodSugarPushDTO dto, Patient patient, String reason) {
        existing.setResult(dto.getBloGluVal());
        existing.setDetectionProject(dto.getTimePeriod());
        existing.setExaminer(dto.getOperatNurse());
        existing.setExaminerId(resolveExaminerId(dto.getOperatNurse()));
        existing.setSpecimenSource("");   // TODO：确认业务默认值
        existing.setDeviceCode("");       // TODO：确认业务默认值
        existing.setRemarks(dto.getBloGluNote());
        existing.setValid(true);
        bloodSugarRepository.save(existing);
        log.info("[BloodSugar] 更新成功({}) id={}, pid={}, patNo={}, result={}",
                reason, existing.getId(), patient.getId(), dto.getPatNo(), dto.getBloGluVal());
        triggerSync();
        return ApiResult.success("血糖修改成功");
    }

    /** 逻辑删除：valid = false。 */
    private ApiResult doDelete(BloodSugar existing, BloodSugarPushDTO dto, Patient patient) {
        existing.setValid(false);
        bloodSugarRepository.save(existing);
        log.info("[BloodSugar] 逻辑删除成功 id={}, pid={}, patNo={}",
                existing.getId(), patient.getId(), dto.getPatNo());
        triggerSync();
        return ApiResult.success("血糖删除成功");
    }

    // ==================== 内部工具 ====================

    /**
     * 根据护士姓名从 account 集合匹配 examinerId。
     *
     * <p>优先按 trueName + "Nurse" 精确匹配；无则降级仅按 trueName 匹配；
     * 重名取第一条；匹配不到返回 null 记日志，不阻断。</p>
     */
    private String resolveExaminerId(String nurseName) {
        if (!StringUtils.hasText(nurseName)) return null;
        Optional<Account> acc = accountRepository.findFirstByTrueNameAndProfession(nurseName, "Nurse");
        if (acc.isPresent()) {
            return acc.get().getId();
        }
        acc = accountRepository.findFirstByTrueName(nurseName);
        if (acc.isPresent()) {
            log.info("[BloodSugar] 护士匹配(不限职业): nurseName={}, accountId={}", nurseName, acc.get().getId());
            return acc.get().getId();
        }
        log.info("[BloodSugar] 未匹配到 examinerId: nurseName={}", nurseName);
        return null;
    }

    /** 必填字段校验。 */
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

    /** 触发既有血糖同步逻辑（全量扫描）。 */
    private void triggerSync() {
        try {
            java.util.Map<String, Integer> stats = bloodSugarSyncService.sync();
            log.info("[BloodSugar] 同步触发完成: {}", stats);
        } catch (Exception e) {
            log.error("[BloodSugar] 同步触发异常（不影响主流程）", e);
        }
    }
}
