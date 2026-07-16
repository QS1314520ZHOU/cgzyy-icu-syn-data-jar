package com.digixmed.icu.viform.repository.smartcare;

import com.digixmed.icu.viform.entity.BloodSugar;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * bloodSugar 集合仓库（SmartCare 库）。
 */
public interface BloodSugarRepository extends MongoRepository<BloodSugar, String> {

    /**
     * 批量按患者 ID 查询有效血糖记录。
     */
    List<BloodSugar> findByPidInAndValidTrue(Collection<String> pids);

    /**
     * 批量按患者 ID + 有效 + time >= 某时间点查询（lookback 窗口）。
     */
    List<BloodSugar> findByPidInAndValidTrueAndTimeGreaterThanEqual(Collection<String> pids, Date since);

    /**
     * 按患者 ID + 检测时间查询有效记录（防重复推送）。
     */
    Optional<BloodSugar> findFirstByPidAndTimeAndValidTrue(String pid, Date time);

    /**
     * 按患者 ID + 检测时间查询（含无效，用于逻辑删除等场景）。
     */
    Optional<BloodSugar> findFirstByPidAndTime(String pid, Date time);
}
