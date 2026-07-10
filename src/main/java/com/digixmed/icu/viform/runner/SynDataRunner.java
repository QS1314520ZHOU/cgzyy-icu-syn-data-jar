package com.digixmed.icu.viform.runner;

import com.digixmed.icu.viform.service.AdmittedPatientBedsideService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 启动时自动执行一次处理流程（可选）。
 *
 * <p>仅当配置 {@code syn.run-on-startup=true} 时启用；
 * 默认关闭，通过 HTTP 接口手动触发（见 SynDataController）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "syn", name = "run-on-startup", havingValue = "true")
public class SynDataRunner implements ApplicationRunner {

    private final AdmittedPatientBedsideService service;

    @Override
    public void run(ApplicationArguments args) {
        log.info("[SynData] run-on-startup=true，开始执行处理流程...");
        int count = service.process();
        log.info("[SynData] 启动处理流程完成，处理记录数: {}", count);
    }
}
