package com.digixmed.icu.viform.repository.smartcare;

import com.digixmed.icu.viform.entity.DFormData;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;

/**
 * dFormData 集合仓库（SmartCare 库）。
 */
public interface DFormDataRepository extends MongoRepository<DFormData, String> {

    /**
     * 按 pid + status + formCode 查询（用于查有效入院评估单）。
     */
    List<DFormData> findByPidAndStatusAndFormCodeIn(String pid, String status, Collection<String> formCodes);

    /**
     * 批量按 pid + status + formCode 查询（避免 N+1）。
     */
    List<DFormData> findByPidInAndStatusAndFormCodeIn(Collection<String> pids, String status,
                                                      Collection<String> formCodes);
}
