package com.helloai.core.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.system.entity.SysPermissionDataRule;

import java.util.Set;

/**
 * 权限数据规则服务（数据权限，受控枚举，BASE-3.3）。
 *
 * <p>规则类型白名单 {@code ALL / DEPT / DEPT_AND_CHILD / CUSTOM}，服务层按枚举解释为
 * 部门范围 → 用户 id 集合，**不拼接任意 SQL**（避免注入与性能风险）。</p>
 */
public interface SysPermissionDataRuleService extends IService<SysPermissionDataRule> {

    /** 查询权限码的数据规则（无则返回 null） */
    SysPermissionDataRule getByPermissionId(Long permissionId);

    /**
     * 保存数据规则（upsert）并同步 {@code sys_permission.rule_flag}。
     *
     * @param ruleType 白名单校验；ALL 时 rule_flag=0（等同未配置），其余置 1
     */
    void saveRule(Long permissionId, String ruleType, String ruleValue);

    /**
     * 按权限码的数据规则解析当前用户可见的 userIds（数据权限过滤）。
     *
     * @return {@code null} = 不限制（未配置 / ALL）；空集合 = 无可见用户（fail-close）
     */
    Set<Long> resolveVisibleUserIds(Long currentUserId, String permissionCode);
}
