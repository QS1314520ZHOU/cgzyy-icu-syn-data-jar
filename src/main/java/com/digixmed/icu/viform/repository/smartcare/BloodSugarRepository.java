package com.digixmed.icu.viform.repository.smartcare;

import com.digixmed.icu.viform.entity.BloodSugar;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.Date;
import java.util.List;

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
}
