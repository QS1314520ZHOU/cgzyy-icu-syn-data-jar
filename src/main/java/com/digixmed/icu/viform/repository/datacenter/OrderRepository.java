package com.digixmed.icu.viform.repository.datacenter;

import com.digixmed.icu.viform.entity.Order;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;

/**
 * VI_ICU_ZYYZ 集合仓库（DataCenter 库 —— Order 医嘱）。
 */
public interface OrderRepository extends MongoRepository<Order, String> {

    /**
     * 按患者 MRN 列表 + 医嘱状态批量查询。
     *
     * <p>医嘱名称在 Java 层按关键词过滤（MongoDB 不支持中文 regex 高效索引）。</p>
     */
    List<Order> findByMrnInAndStatus(Collection<String> mrns, String status);
}
