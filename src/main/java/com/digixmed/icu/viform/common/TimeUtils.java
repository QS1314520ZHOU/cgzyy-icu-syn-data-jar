package com.digixmed.icu.viform.common;

import java.util.Date;

/**
 * 时间工具。
 */
public final class TimeUtils {

    private static final long MINUTE_MS = 60_000L;

    private TimeUtils() {
    }

    /**
     * 时间截断到分钟（秒、毫秒归零）。
     *
     * <p>例：14:08:50 → 14:08:00，14:08:40 → 14:08:00。</p>
     *
     * <p>用整除实现，不依赖时区（常见时区偏移均为整分钟）；
     * 用 floorDiv 保证负时间戳也向下取整。</p>
     *
     * @param date 原始时间，可为 null
     * @return 截断后的时间；入参为 null 时返回 null
     */
    public static Date truncateToMinute(Date date) {
        if (date == null) {
            return null;
        }
        return new Date(Math.floorDiv(date.getTime(), MINUTE_MS) * MINUTE_MS);
    }
}
