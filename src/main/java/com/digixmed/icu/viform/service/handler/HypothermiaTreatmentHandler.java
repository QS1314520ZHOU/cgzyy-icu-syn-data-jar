package com.digixmed.icu.viform.service.handler;

import com.digixmed.icu.viform.entity.Bedside;
import com.digixmed.icu.viform.entity.Patient;
import com.digixmed.icu.viform.service.BedsideCodeHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 示例处理器：亚低温治疗 (code = param_亚低温治疗)。
 *
 * <p>当前仅打印日志占位，具体业务逻辑（用户后续给出）请在 {@link #handle} 内实现。</p>
 */
@Slf4j
@Component
public class HypothermiaTreatmentHandler implements BedsideCodeHandler {

    /** 与 bedside.code 完全一致。 */
    public static final String CODE = "param_亚低温治疗";

    @Override
    public String supportedCode() {
        return CODE;
    }

    @Override
    public void handle(Patient patient, Bedside bedside) {
        // TODO: 在此实现具体业务逻辑（用户后续给出）。
        log.info("[亚低温治疗] patient={}({}), strVal={}, fVal={}, time={}, valid={}",
                patient.getName(), patient.getId(),
                bedside.getStrVal(), bedside.getFVal(), bedside.getTime(), bedside.getValid());
    }
}
