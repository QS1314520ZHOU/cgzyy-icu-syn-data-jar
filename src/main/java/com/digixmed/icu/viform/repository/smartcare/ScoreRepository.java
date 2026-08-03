package com.digixmed.icu.viform.repository.smartcare;

import com.digixmed.icu.viform.entity.Score;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;

/**
 * score 集合仓库（SmartCare 库）。
 */
public interface ScoreRepository extends MongoRepository<Score, String> {

    /**
     * 批量按 pid + scoreType + valid 查询（避免 N+1）。
     */
    List<Score> findByPidInAndScoreTypeAndValidTrue(Collection<String> pids, String scoreType);
}
