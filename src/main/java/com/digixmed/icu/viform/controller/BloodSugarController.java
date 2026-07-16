package com.digixmed.icu.viform.controller;

import com.digixmed.icu.viform.common.ApiResult;
import com.digixmed.icu.viform.dto.BloodSugarPushDTO;
import com.digixmed.icu.viform.service.BloodSugarReceiveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 血糖数据接收联调接口。
 *
 * <p>平台方通过 POST /data/bloodSugar 推送血糖数据。</p>
 */
@Slf4j
@RestController
@RequestMapping("/data")
@RequiredArgsConstructor
public class BloodSugarController {

    private final BloodSugarReceiveService bloodSugarReceiveService;

    /**
     * 接收平台方推送的血糖数据。
     *
     * <pre>
     * curl -X POST http://&lt;ip&gt;:30001/data/bloodSugar \
     *   -H "Content-Type: application/json" \
     *   -d '{"patId":"...","patName":"...","patNo":"...", ...}'
     * </pre>
     */
    @PostMapping("/bloodSugar")
    public ApiResult receive(@RequestBody BloodSugarPushDTO dto) {
        log.info("[API] POST /data/bloodSugar - 收到血糖推送 patNo={}, operationType={}",
                dto != null ? dto.getPatNo() : "null",
                dto != null ? dto.getOperationType() : "null");
        long start = System.currentTimeMillis();

        ApiResult result = bloodSugarReceiveService.process(dto);

        long elapsed = System.currentTimeMillis() - start;
        log.info("[API] POST /data/bloodSugar 完成: status={}, msg={}, 耗时={}ms",
                result.getStatus(), result.getMsg(), elapsed);
        return result;
    }
}
