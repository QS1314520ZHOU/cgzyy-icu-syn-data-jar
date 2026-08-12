package com.digixmed.icu.viform.repository.smartcare;

import com.digixmed.icu.viform.entity.Account;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 账户仓库（SmartCare 库 account 集合）。
 */
public interface AccountRepository extends MongoRepository<Account, String> {

    /**
     * 按 ID 列表批量查询账户。
     */
    List<Account> findByIdIn(Collection<String> ids);

    /**
     * 按真实姓名和职业查询第一个账户。
     */
    Optional<Account> findFirstByTrueNameAndProfession(String trueName, String profession);

    /**
     * 按真实姓名查询第一个账户。
     */
    Optional<Account> findFirstByTrueName(String trueName);
}
