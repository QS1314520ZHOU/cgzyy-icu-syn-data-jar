package com.digixmed.icu.viform.service;

import com.digixmed.icu.viform.entity.Bedside;
import com.digixmed.icu.viform.entity.Patient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 根据 bedside.code 将记录分发到对应的 {@link BedsideCodeHandler}。
 *
 * <p>启动时自动收集所有 Handler Bean 并按 supportedCode 注册。</p>
 */
@Slf4j
@Component
public class BedsideCodeDispatcher {

    private final Map<String, BedsideCodeHandler> handlerByCode = new HashMap<>();

    public BedsideCodeDispatcher(List<BedsideCodeHandler> handlers) {
        for (BedsideCodeHandler handler : handlers) {
            handlerByCode.put(handler.supportedCode(), handler);
            log.info("[SynData] 注册 bedside 处理器: code={}, handler={}",
                    handler.supportedCode(), handler.getClass().getSimpleName());
        }
    }

    /**
     * 分发单条 bedside 记录。若无匹配 handler 则跳过（仅记录日志）。
     */
    public void dispatch(Patient patient, Bedside bedside) {
        String code = bedside.getCode();
        if (!StringUtils.hasText(code)) {
            return;
        }
        BedsideCodeHandler handler = handlerByCode.get(code);
        if (handler == null) {
            log.debug("[SynData] 未找到 code={} 的处理器，跳过 (pid={})", code, bedside.getPid());
            return;
        }
        handler.handle(patient, bedside);
    }
}
