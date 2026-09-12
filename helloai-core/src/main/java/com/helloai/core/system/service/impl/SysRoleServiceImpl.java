package com.helloai.core.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.core.system.entity.SysRole;
import com.helloai.core.system.entity.SysRolePermission;
import com.helloai.core.system.entity.SysUserRole;
import com.helloai.core.system.mapper.SysRoleMapper;
import com.helloai.core.system.mapper.SysRolePermissionMapper;
import com.helloai.core.system.mapper.SysUserRoleMapper;
import com.helloai.core.system.service.SysRoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 系统角色服务实现（RBAC 管理与授权）。
 *
 * <p>关联表（sys_role_permission / sys_user_role）先删后插、全量覆盖，保持幂等；
 * 内置角色（SUPER_ADMIN / ADMIN）不可删除，避免破坏种子授权基线。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements SysRoleService {

    private final SysRolePermissionMapper sysRolePermissionMapper;
    private final SysUserRoleMapper sysUserRoleMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysRole createRole(String code, String name, String description, String status, Integer sort) {
        Long count = lambdaQuery().eq(SysRole::getCode, code).count();
        if (count != null && count > 0) {
            throw new BizException("角色编码 '" + code + "' 已存在");
        }
        SysRole role = new SysRole();
        role.setCode(code);
        role.setName(name);
        role.setDescription(description);
        role.setStatus(status != null ? status : "ACTIVE");
        role.setSort(sort != null ? sort : 0);
        save(role);
        log.info("角色创建: code={}, id={}", code, role.getId());
        return role;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysRole updateRole(Long id, String name, String description, String status, Integer sort) {
        SysRole role = getById(id);
        if (role == null) {
            throw new BizException("角色不存在: " + id);
        }
        if (name != null) role.setName(name);
        if (description != null) role.setDescription(description);
        if (status != null) role.setStatus(status);
        if (sort != null) role.setSort(sort);
        updateById(role);
        log.info("角色更新: id={}, code={}", id, role.getCode());
        return role;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(Long id) {
        SysRole role = getById(id);
        if (role == null) {
            return;
        }
        if ("SUPER_ADMIN".equals(role.getCode()) || "ADMIN".equals(role.getCode())) {
            throw new BizException("内置角色不可删除");
        }
        removeById(id);
        sysRolePermissionMapper.delete(Wrappers.<SysRolePermission>lambdaQuery()
                .eq(SysRolePermission::getRoleId, id));
        log.info("角色删除: id={}, code={}", id, role.getCode());
    }

    @Override
    public List<Long> listPermissionIds(Long roleId) {
        return sysRolePermissionMapper.selectList(Wrappers.<SysRolePermission>lambdaQuery()
                        .eq(SysRolePermission::getRoleId, roleId))
                .stream()
                .map(SysRolePermission::getPermissionId)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignPermissions(Long roleId, List<Long> permissionIds) {
        assignPermissions(roleId, permissionIds, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignPermissions(Long roleId, List<Long> permissionIds, List<Long> lastPermissionIds) {
        SysRole role = getById(roleId);
        if (role == null) {
            throw new BizException("角色不存在: " + roleId);
        }
        if ("SUPER_ADMIN".equals(role.getCode())) {
            throw new BizException("超级管理员为内置全权限角色，不可修改权限");
        }
        List<Long> target = permissionIds != null ? permissionIds : List.of();
        // 差异更新（BASE-2.3）：提供绑定前快照时按差集批量增删，避免全量删插无效写
        if (lastPermissionIds != null) {
            Set<Long> targetSet = new HashSet<>(target);
            Set<Long> lastSet = new HashSet<>(lastPermissionIds);
            // 新增：target - last
            List<Long> toAdd = target.stream().filter(id -> !lastSet.contains(id)).toList();
            for (Long permissionId : toAdd) {
                SysRolePermission rp = new SysRolePermission();
                rp.setRoleId(roleId);
                rp.setPermissionId(permissionId);
                sysRolePermissionMapper.insert(rp);
            }
            // 删除：last - target
            List<Long> toRemove = lastPermissionIds.stream().filter(id -> !targetSet.contains(id)).toList();
            if (!toRemove.isEmpty()) {
                sysRolePermissionMapper.delete(Wrappers.<SysRolePermission>lambdaQuery()
                        .eq(SysRolePermission::getRoleId, roleId)
                        .in(SysRolePermission::getPermissionId, toRemove));
            }
            log.info("角色权限差异更新: roleId={}, add={}, remove={}", roleId, toAdd.size(), toRemove.size());
            return;
        }
        // 兼容路径：无快照时全量删插（原行为）
        sysRolePermissionMapper.delete(Wrappers.<SysRolePermission>lambdaQuery()
                .eq(SysRolePermission::getRoleId, roleId));
        if (!target.isEmpty()) {
            for (Long permissionId : target) {
                SysRolePermission rp = new SysRolePermission();
                rp.setRoleId(roleId);
                rp.setPermissionId(permissionId);
                sysRolePermissionMapper.insert(rp);
            }
        }
        log.info("角色权限绑定: roleId={}, permissionCount={}", roleId, target.size());
    }

    @Override
    public List<Long> listRoleIdsByUser(Long userId) {
        return sysUserRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                        .eq(SysUserRole::getUserId, userId))
                .stream()
                .map(SysUserRole::getRoleId)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignUserRoles(Long userId, List<Long> roleIds) {
        sysUserRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getUserId, userId));
        if (roleIds != null && !roleIds.isEmpty()) {
            for (Long roleId : roleIds) {
                SysUserRole ur = new SysUserRole();
                ur.setUserId(userId);
                ur.setRoleId(roleId);
                sysUserRoleMapper.insert(ur);
            }
        }
        log.info("用户角色分配: userId={}, roleCount={}", userId, roleIds == null ? 0 : roleIds.size());
    }

    @Override
    public Map<Long, List<String>> listRoleCodesByUserIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<SysUserRole> links = sysUserRoleMapper.selectList(
                Wrappers.<SysUserRole>lambdaQuery().in(SysUserRole::getUserId, userIds));
        if (links.isEmpty()) {
            return Map.of();
        }
        Set<Long> roleIds = links.stream()
                .map(SysUserRole::getRoleId)
                .collect(Collectors.toSet());
        Map<Long, String> codeById = baseMapper.selectBatchIds(roleIds).stream()
                .collect(Collectors.toMap(SysRole::getId, SysRole::getCode));
        Map<Long, List<String>> result = new HashMap<>();
        for (SysUserRole link : links) {
            String code = codeById.get(link.getRoleId());
            if (code != null) {
                result.computeIfAbsent(link.getUserId(), k -> new ArrayList<>()).add(code);
            }
        }
        return result;
    }
}