package com.digixmed.icu.viform.repository.smartcare;

import com.digixmed.icu.viform.entity.Account;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * account 集合仓库（SmartCare 库）。
 *
 * <p>用于根据护士姓名匹配账号 ID。</p>
 */
public interface AccountRepository extends MongoRepository<Account, String> {

    /**
     * 按姓名模糊匹配（TODO: 字段名需确认，account 集合中可能是 name / userName / displayName）。
     */
    List<Account> findByName(String name);
}
