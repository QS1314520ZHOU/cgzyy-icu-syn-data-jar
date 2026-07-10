package com.digixmed.icu.viform.repository;

import com.digixmed.icu.viform.entity.Bedside;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;

/**
 * bedside 集合仓库。
 */
public interface BedsideRepository extends MongoRepository<Bedside, String> {

    /** 按患者 ID 查询全部 bedside 记录（bedside.pid = patient.id）。 */
    List<Bedside> findByPid(String pid);

    /** 批量按患者 ID 查询 bedside 记录。 */
    List<Bedside> findByPidIn(Collection<String> pids);

    /** 按患者 ID + 业务编码查询。 */
    List<Bedside> findByPidAndCode(String pid, String code);

    /** 批量按患者 ID + 编码集合查询。 */
    List<Bedside> findByPidInAndCodeIn(Collection<String> pids, Collection<String> codes);

    /** 仅查询有效记录。 */
    List<Bedside> findByPidAndValidTrue(String pid);
}
