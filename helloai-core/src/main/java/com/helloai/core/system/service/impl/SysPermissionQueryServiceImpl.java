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
import java.util.HashMap;
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
                Wrappers.<SysPermission>lambdaQuery()
                        .orderByAsc(SysPermission::getSort)
                        .orderByAsc(SysPermission::getId));
    }

    @Override
    public List<SysPermission> listAssignable() {
        return listAll().stream()
                .filter(p -> !isMenuGroup(p.getCode()))
                .toList();
    }

    /** 分组目录按 code 前缀识别（与 V143/V144 的建组约定同一事实源）。 */
    private static boolean isMenuGroup(String code) {
        return code != null && code.startsWith(MENU_GROUP_CODE_PREFIX);
    }

    @Override
    public List<SysPermission> listMenuTree(Long userId) {
        List<SysPermission> allMenus = sysPermissionMapper.selectList(
                Wrappers.<SysPermission>lambdaQuery()
                        .eq(SysPermission::getType, "MENU")
                        .orderByAsc(SysPermission::getSort)
                        // sort 允许重复（历史数据存在 1/1、19/19），缺兜底键时同级顺序由 DB 决定
                        .orderByAsc(SysPermission::getId));
        if (allMenus.isEmpty()) {
            return List.of();
        }
        // 用户可见权限码集合；SUPER_ADMIN 命中 "*" 通配全量可见
        Set<String> allowed = new HashSet<>(listPermissionCode(userId));
        boolean superAdmin = allowed.contains("*");

        Map<Long, SysPermission> byId = allMenus.stream()
                .collect(Collectors.toMap(SysPermission::getId, p -> p));
        // DB 层存在子节点的父菜单集合（用于「无可见子节点则剔除父节点」判定）
        Set<Long> dbParentIds = allMenus.stream()
                .map(SysPermission::getParentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 可见集合 = 自身已授权 ∪ 已授权节点的祖先链。
        // 祖先放行的必要性：目录（分组）不是权限点。若只按自身权限码过滤，未单独授予
        // 分组码的角色会连同整个分组一起消失——子节点的 parent 不在可见集合里，
        // 既不进 roots 也不进任何 children，整棵子树静默丢失且无日志可查。
        Set<Long> visibleIds = allMenus.stream()
                .filter(p -> superAdmin || allowed.contains(p.getCode()))
                .map(SysPermission::getId)
                .collect(Collectors.toCollection(HashSet::new));
        for (Long id : List.copyOf(visibleIds)) {
            SysPermission cursor = byId.get(id);
            Long parentId = cursor == null ? null : cursor.getParentId();
            // 只沿真实存在的祖先向上走：悬空 parent 若先被加入 visibleIds，后续会把子节点
            // 归到一个不存在的父分组里，导致整棵子树静默丢失。
            // add 返回 false 说明该祖先已在集合中（同时兼作脏数据成环的终止条件）。
            while (parentId != null && byId.containsKey(parentId) && visibleIds.add(parentId)) {
                parentId = byId.get(parentId).getParentId();
            }
        }

        // 按 parent 归组；allMenus 已按 sort,id 排序，故各组内自然有序
        Map<Long, List<SysPermission>> childrenByParent = new HashMap<>();
        List<SysPermission> roots = new ArrayList<>();
        for (SysPermission p : allMenus) {
            if (!visibleIds.contains(p.getId())) {
                continue;
            }
            Long parentId = p.getParentId();
            SysPermission parent = parentId == null ? null : byId.get(parentId);
            if (parent == null || !visibleIds.contains(parentId)) {
                // 一级菜单；parent 已不存在（脏数据）的孤儿节点同样兜底为一级，避免静默丢失
                roots.add(p);
            } else {
                childrenByParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(p);
            }
        }
        return roots.stream()
                .map(p -> attachVisibleChildren(p, childrenByParent, dbParentIds))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 递归挂接可见子节点，并自底向上剔除空目录。
     *
     * @return 该节点自身，或 {@code null} 表示应剔除（空目录：有子节点但全不可见，
     *         或本身就没有页面且没有子节点）
     */
    private SysPermission attachVisibleChildren(SysPermission node,
                                                Map<Long, List<SysPermission>> childrenByParent,
                                                Set<Long> dbParentIds) {
        List<SysPermission> candidates = childrenByParent.get(node.getId());
        List<SysPermission> children = new ArrayList<>();
        if (candidates != null) {
            for (SysPermission child : candidates) {
                SysPermission attached = attachVisibleChildren(child, childrenByParent, dbParentIds);
                if (attached != null) {
                    children.add(attached);
                }
            }
        }
        // 剪枝必须逐层做，不能只判根层：否则出现「标题在、项全没」的空目录。
        // 目录本身没有 component/externalLink，落到前端只会渲染成「点了没路由」的菜单项，
        // 因此即便 DB 里没有子节点（dbParentIds 不含它）也要剔除。
        boolean pageLike = hasText(node.getComponent()) || hasText(node.getExternalLink());
        if (children.isEmpty() && (dbParentIds.contains(node.getId()) || !pageLike)) {
            return null;
        }
        node.setChildren(children.isEmpty() ? null : children);
        return node;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public List<SysPermission> listPermissionTree() {
        List<SysPermission> all = sysPermissionMapper.selectList(
                Wrappers.<SysPermission>lambdaQuery()
                        .orderByAsc(SysPermission::getSort)
                        .orderByAsc(SysPermission::getId));
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