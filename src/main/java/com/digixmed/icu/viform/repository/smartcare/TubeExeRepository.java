package com.digixmed.icu.viform.repository.smartcare;

import com.digixmed.icu.viform.entity.TubeExe;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * 管道护理执行记录仓库（SmartCare 库）。
 */
public interface TubeExeRepository extends MongoRepository<TubeExe, String> {

    /**
     * 按患者 ID 列表查询管道护理记录。
     */
    List<TubeExe> findByPidIn(List<String> pids);
}
