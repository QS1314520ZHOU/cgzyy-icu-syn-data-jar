package com.digixmed.icu.viform.repository.smartcare;

import com.digixmed.icu.viform.entity.ConfigTubeView;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * 管道配置视图仓库（SmartCare 库）。
 */
public interface ConfigTubeViewRepository extends MongoRepository<ConfigTubeView, String> {

    /**
     * 查询所有有效的管道配置。
     */
    List<ConfigTubeView> findByValidTrue();

    /**
     * 按管道类型查询配置。
     */
    ConfigTubeView findByTubeTypeAndValidTrue(String tubeType);
}
