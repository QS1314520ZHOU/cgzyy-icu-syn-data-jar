package com.digixmed.icu.viform.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 管道护理记录同步配置。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "tube-nursing-sync")
public class TubeNursingSyncProperties {

	private boolean enabled = true;

	private String timezone = "Asia/Shanghai";

	private long scanIntervalMs = 60_000L;

	private long initialDelayMs = 30_000L;

	/** 同步时间范围（天）。只同步最近N天内的管道记录。 */
	private int syncDays = 3;

	/** 每批处理的患者数量。避免一次性查询过多患者导致数据库压力过大。 */
	private int batchSize = 20;
}
