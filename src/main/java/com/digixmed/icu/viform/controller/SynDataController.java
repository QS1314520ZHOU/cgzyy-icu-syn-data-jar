package com.digixmed.icu.viform.controller;

import com.digixmed.icu.viform.entity.Bedside;
import com.digixmed.icu.viform.service.AdmittedPatientBedsideService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 手动触发 / 调试接口。若不需要 HTTP 接口，可删除本类及 web 依赖。
 */
@RestController
@RequestMapping("/syn")
@RequiredArgsConstructor
public class SynDataController {

    private final AdmittedPatientBedsideService service;

    /** 健康检查。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP");
    }

    /** 手动触发一次完整处理流程。 */
    @PostMapping("/process")
    public Map<String, Object> process() {
        int count = service.process();
        return Map.of("handled", count);
    }

    /** 调试：查询某在院患者的 bedside 记录。 */
    @GetMapping("/patients/{patientId}/bedsides")
    public List<Bedside> bedsides(@PathVariable String patientId) {
        return service.findBedsidesForPatient(patientId);
    }
}
