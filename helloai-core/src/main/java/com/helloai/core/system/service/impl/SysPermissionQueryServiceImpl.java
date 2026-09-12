package com.helloai.core.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.helloai.core.system.entity.SysPermission;
import com.helloai.core.system.entity.SysRole;
import com.helloai.core.system.entity.SysRolePermission;
import com.helloai.core.system.entity.SysUserRole;
import com.helloai.core.system.mapper.SysPermissionMapper;
import com.helloai.core.system.mapper.SysRoleMapper;
import com.helloai.core.system.mapper.SysRolePermissionMapper;
import com.helloai.core.system.mapper.SysUserRoleMapper;
import com.helloai.core.system.service.SysPermissionQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限查询服务实现（RBAC 读侧，只读不写）。
 *
 * <p>查询链路：{@code sys_user_role → sys_role → sys_role_permission → sys_permission}，
 * 全部走 MyBatis-Plus BaseMapper（自带 @TableLogic 逻辑删除过滤），不直捅 XML。</p>
 */
@Service
@RequiredArgsConstructor
public class SysPermissionQueryServiceImpl implements SysPermissionQueryService {

    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;
    private final SysPermissionMapper sysPermissionMapper;

    @Override
    public List<String> listRoleCode(Long userId) {
        return listActiveRoles(userId).stream()
                .map(SysRole::getCode)
                .distinct()
                .toList();
    }

    @Override
    public List<String> listPermissionCode(Long userId) {
        List<SysRole> roles = listActiveRoles(userId);
        boolean superAdmin = roles.stream().anyMatch(r -> "SUPER_ADMIN".equals(r.getCode()));
        if (superAdmin) {
            return List.of("*");
        }
        List<Long> roleIds = roles.stream().map(SysRole::getId).toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        List<Long> permissionIds = sysRolePermissionMapper.selectList(
                        Wrappers.<SysRolePermission>lambdaQuery()
                                .in(SysRolePermission::getRoleId, roleIds))
                .stream()
                .map(SysRolePermission::getPermissionId)
                .distinct()
                .toList();
        if (permissionIds.isEmpty()) {
            return List.of();
        }
        return sysPermissionMapper.selectBatchIds(permissionIds).stream()
                .map(SysPermission::getCode)
                .distinct()
                .toList();
    }

    private List<SysRole> listActiveRoles(Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<Long> roleIds = sysUserRoleMapper.selectList(
                        Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, userId))
                .stream()
                .map(SysUserRole::getRoleId)
                .toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return sysRoleMapper.selectBatchIds(roleIds).stream()
                .filter(r -> "ACTIVE".equals(r.getStatus()))
                .toList();
    }

    @Override
    public List<SysPermission> listAll() {
        return sysPermissionMapper.selectList(
                Wrappers.<SysPermission>lambdaQuery().orderByAsc(SysPermission::getSort));
    }

    @Override
    public List<SysPermission> listMenuTree(Long userId) {
        List<SysPermission> allMenus = sysPermissionMapper.selectList(
                Wrappers.<SysPermission>lambdaQuery()
                        .eq(SysPermission::getType, "MENU")
                        .orderByAsc(SysPermission::getSort));
        if (allMenus.isEmpty()) {
            return List.of();
        }
        // 用户可见权限码集合；SUPER_ADMIN 命中 "*" 通配全量可见
        Set<String> allowed = new HashSet<>(listPermissionCode(userId));
        boolean superAdmin = allowed.contains("*");
        List<SysPermission> visible = allMenus.stream()
                .filter(p -> superAdmin || allowed.contains(p.getCode()))
                .toList();
        // DB 层存在子节点的父菜单集合（用于「无可见子节点则剔除父节点」判定）
        Set<Long> dbParentIds = allMenus.stream()
                .map(SysPermission::getParentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, SysPermission> byId = visible.stream()
                .collect(Collectors.toMap(SysPermission::getId, p -> p));
        List<SysPermission> roots = new ArrayList<>();
        for (SysPermission p : visible) {
            if (p.getParentId() == null) {
                roots.add(p);
                continue;
            }
            SysPermission parent = byId.get(p.getParentId());
            if (parent != null) {
                List<SysPermission> children = parent.getChildren();
                if (children == null) {
                    children = new ArrayList<>();
                    parent.setChildren(children);
                }
                children.add(p);
            }
        }
        return roots.stream()
                .filter(p -> !dbParentIds.contains(p.getId())
                        || (p.getChildren() != null && !p.getChildren().isEmpty()))
                .toList();
    }

    @Override
    public List<SysPermission> listPermissionTree() {
        List<SysPermission> all = sysPermissionMapper.selectList(
                Wrappers.<SysPermission>lambdaQuery().orderByAsc(SysPermission::getSort));
        if (all.isEmpty()) {
            return List.of();
        }
        Map<Long, SysPermission> byId = all.stream()
                .collect(Collectors.toMap(SysPermission::getId, p -> p));
        List<SysPermission> roots = new ArrayList<>();
        for (SysPermission p : all) {
            // type=API 无层级：一律平铺为根级叶子
            if (p.getParentId() == null || !"MENU".equals(p.getType())) {
                roots.add(p);
                continue;
            }
            SysPermission parent = byId.get(p.getParentId());
            if (parent != null && "MENU".equals(parent.getType())) {
                List<SysPermission> children = parent.getChildren();
                if (children == null) {
                    children = new ArrayList<>();
                    parent.setChildren(children);
                }
                children.add(p);
            } else {
                // 父节点缺失或父不是 MENU：兜底平铺，避免孤儿行
                roots.add(p);
            }
        }
        return roots;
    }
}