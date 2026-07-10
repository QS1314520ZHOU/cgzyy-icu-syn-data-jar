package com.digixmed.icu.viform.service;

import com.digixmed.icu.viform.entity.Bedside;
import com.digixmed.icu.viform.entity.Patient;

/**
 * bedside.code 业务处理器接口（策略模式）。
 *
 * <p>每种 code 对应一个实现类，返回 {@link #supportedCode()} 用于注册路由。</p>
 */
public interface BedsideCodeHandler {

    /** 该处理器支持的 bedside.code。 */
    String supportedCode();

    /**
     * 处理单条 bedside 记录。
     *
     * @param patient 关联的在院患者
     * @param bedside 当前 bedside 记录
     */
    void handle(Patient patient, Bedside bedside);
}
