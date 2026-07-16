package com.digixmed.icu.viform.repository.smartcare;

import com.digixmed.icu.viform.entity.Account;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * account 集合仓库（SmartCare 库）。
 *
 * <p>用于根据护士姓名（trueName）匹配账号 ID。</p>
 */
public interface AccountRepository extends MongoRepository<Account, String> {

    /**
     * 按护士真实姓名 + 职业精确匹配第一条。
     *
     * <p>重名时取第一条，不阻塞流程。</p>
     */
    Optional<Account> findFirstByTrueNameAndProfession(String trueName, String profession);

    /**
     * 仅按护士真实姓名匹配第一条（不限定职业）。
     */
    Optional<Account> findFirstByTrueName(String trueName);
}
