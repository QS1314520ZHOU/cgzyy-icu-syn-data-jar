package com.digixmed.icu.viform.service;

import com.digixmed.icu.viform.entity.Account;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 护士角色判断工具类。
 */
public class NurseRoleUtils {

    /** 护士角色集合 */
    private static final Set<String> NURSE_ROLES = new HashSet<>(Arrays.asList(
            "Nurse", "Matron", "PracticeNurse", "NurseLeader"
    ));

    /**
     * 判断账号是否是护士角色。
     *
     * @param account 账号信息
     * @return true=是护士角色，false=是医生或其他角色
     */
    public static boolean isNurseRole(Account account) {
        if (account == null || !StringUtils.hasText(account.getProfession())) {
            return false;
        }
        return NURSE_ROLES.contains(account.getProfession());
    }
}
