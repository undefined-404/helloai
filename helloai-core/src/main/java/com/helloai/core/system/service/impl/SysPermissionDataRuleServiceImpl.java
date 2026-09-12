package com.helloai.core.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.core.system.entity.SysPermission;
import com.helloai.core.system.entity.SysPermissionDataRule;
import com.helloai.core.system.mapper.SysPermissionDataRuleMapper;
import com.helloai.core.system.mapper.SysPermissionMapper;
import com.helloai.core.system.service.SysDepartService;
import com.helloai.core.system.service.SysPermissionDataRuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 权限数据规则服务实现（数据权限，受控枚举，BASE-3.3）。
 *
 * <p>解析链路：权限码 → rule_flag → 规则类型 → 部门范围 → 用户 id 集合。
 * 非法规则类型按 fail-close 处理（返回空集合，看不到数据）并记 WARN，避免脏配置放大数据可见面。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysPermissionDataRuleServiceImpl
        extends ServiceImpl<SysPermissionDataRuleMapper, SysPermissionDataRule>
        implements SysPermissionDataRuleService {

    /** 规则类型白名单 */
    private static final Set<String> RULE_TYPES = Set.of("ALL", "DEPT", "DEPT_AND_CHILD", "CUSTOM");

    private final SysPermissionMapper sysPermissionMapper;
    private final SysDepartService sysDepartService;

    @Override
    public SysPermissionDataRule getByPermissionId(Long permissionId) {
        if (permissionId == null) {
            return null;
        }
        List<SysPermissionDataRule> rules = list(Wrappers.<SysPermissionDataRule>lambdaQuery()
                .eq(SysPermissionDataRule::getPermissionId, permissionId));
        return rules.isEmpty() ? null : rules.get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveRule(Long permissionId, String ruleType, String ruleValue) {
        SysPermission perm = sysPermissionMapper.selectById(permissionId);
        if (perm == null) {
            throw new BizException("权限码不存在: " + permissionId);
        }
        String type = ruleType == null ? "ALL" : ruleType.trim().toUpperCase(Locale.ROOT);
        if (!RULE_TYPES.contains(type)) {
            throw new BizException("数据规则类型仅支持 ALL / DEPT / DEPT_AND_CHILD / CUSTOM");
        }
        String value = "CUSTOM".equals(type) ? trimToNull(ruleValue) : null;
        if ("CUSTOM".equals(type) && value == null) {
            throw new BizException("CUSTOM 规则必须指定部门范围");
        }

        // upsert：先删后插（一个权限码一条规则）
        remove(Wrappers.<SysPermissionDataRule>lambdaQuery()
                .eq(SysPermissionDataRule::getPermissionId, permissionId));
        SysPermissionDataRule rule = new SysPermissionDataRule();
        rule.setPermissionId(permissionId);
        rule.setRuleType(type);
        rule.setRuleValue(value);
        save(rule);

        // 同步 rule_flag：ALL 视为未配置（不过滤）
        SysPermission update = new SysPermission();
        update.setId(permissionId);
        update.setRuleFlag("ALL".equals(type) ? 0 : 1);
        sysPermissionMapper.updateById(update);

        log.info("数据规则保存: permissionId={}, ruleType={}, ruleValue={}", permissionId, type, value);
    }

    @Override
    public Set<Long> resolveVisibleUserIds(Long currentUserId, String permissionCode) {
        if (currentUserId == null || permissionCode == null) {
            return null;
        }
        SysPermission perm;
        List<SysPermission> perms = sysPermissionMapper.selectList(Wrappers.<SysPermission>lambdaQuery()
                .eq(SysPermission::getCode, permissionCode));
        perm = perms.isEmpty() ? null : perms.get(0);
        if (perm == null || perm.getRuleFlag() == null || perm.getRuleFlag() != 1) {
            return null;
        }
        SysPermissionDataRule rule = getByPermissionId(perm.getId());
        if (rule == null || rule.getRuleType() == null || "ALL".equals(rule.getRuleType())) {
            return null;
        }
        String type = rule.getRuleType().toUpperCase(Locale.ROOT);
        List<Long> departScope = switch (type) {
            case "DEPT" -> sysDepartService.listDepartIdsByUser(currentUserId);
            case "DEPT_AND_CHILD" -> expandDepartAndChild(currentUserId);
            case "CUSTOM" -> parseDepartIds(rule.getRuleValue());
            default -> null;
        };
        if (departScope == null) {
            // 非法规则类型（脏数据）：fail-close，避免放大可见面
            log.warn("未知数据规则类型，按无可见数据处理: permissionId={}, ruleType={}", perm.getId(), rule.getRuleType());
            return Set.of();
        }
        if (departScope.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(sysDepartService.listUserIdsByDepartIds(departScope));
    }

    /** 当前用户部门 + 各自全部后代部门 */
    private List<Long> expandDepartAndChild(Long currentUserId) {
        List<Long> ownDepartIds = sysDepartService.listDepartIdsByUser(currentUserId);
        Set<Long> scope = new LinkedHashSet<>();
        for (Long departId : ownDepartIds) {
            scope.addAll(sysDepartService.listDepartAndChildIds(departId));
        }
        return new ArrayList<>(scope);
    }

    /** 解析 CUSTOM 部门 ID（逗号分隔）；非法项丢弃 */
    private List<Long> parseDepartIds(String ruleValue) {
        if (ruleValue == null || ruleValue.isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String part : ruleValue.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.valueOf(p));
            } catch (NumberFormatException e) {
                log.warn("数据规则部门 ID 非法，已忽略: {}", p);
            }
        }
        return ids;
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
