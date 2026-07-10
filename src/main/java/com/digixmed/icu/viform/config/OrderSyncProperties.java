package com.digixmed.icu.viform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 医嘱驱动 bedside 同步配置绑定（application.yml 中的 {@code order-sync} 段）。
 */
@Data
@ConfigurationProperties(prefix = "order-sync")
public class OrderSyncProperties {

    /** 同步任务名称 */
    private String name = "bisi-liquid";

    /** 目标 bedside.code */
    private String targetCode = "param_biSiLiquid";

    /** 筛选的医嘱状态 */
    private String orderStatus = "在执行";

    /** 写入 bedside 的系统账号 */
    private String editUser = "6a1f9f01e467916d244bbdaa";

    /** 医嘱名称命中关键词列表 */
    private List<String> orderNameKeywords = new ArrayList<>();
}
