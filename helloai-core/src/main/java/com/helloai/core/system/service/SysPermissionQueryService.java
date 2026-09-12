package com.helloai.core.system.service;

import com.helloai.core.system.entity.SysPermission;

import java.util.List;

/**
 * 权限查询服务（RBAC 读侧）。
 *
 * <p>供 Sa-Token {@code StpInterface} 与「前端菜单过滤」后端接口复用：按 userId 查出
 * 角色码与权限码，单一事实源来自 {@code sys_user_role → sys_role → sys_role_permission →
 * sys_permission}。{@code SUPER_ADMIN} 超级管理员约定返回 {@code "*"} 通配（全权限），
 * 不落关联表。</p>
 */
public interface SysPermissionQueryService {

    /**
     * 查询用户角色码列表（如 {@code [SUPER_ADMIN]} / {@code [ADMIN]}）。
     */
    List<String> listRoleCode(Long userId);

    /**
     * 查询用户权限码列表；超级管理员返回 {@code ["*"]}。
     */
    List<String> listPermissionCode(Long userId);

    /**
     * 列出全部权限码实体（供「角色授权」界面勾选，以及前端菜单按码过滤）。
     */
    List<SysPermission> listAll();

    /**
     * 查询用户可见菜单树（type=MENU，按权限码过滤后 parent 挂接）。
     *
     * <p>SUPER_ADMIN 经 {@code "*"} 通配全量可见；父菜单在「DB 有子但过滤后无可见子节点」
     * 时剔除（如 ADMIN 无 user/role/permission:view 时隐藏「系统设置」）。返回树为
     * 一级 → 多级，每级按 sort 升序。</p>
     */
    List<SysPermission> listMenuTree(Long userId);

    /**
     * 查询全量权限树（管理侧，不做权限码过滤）。
     *
     * <p>供「菜单/权限管理」界面使用：type=MENU 按 parent 挂接成树（NULL=根），
     * type=API 作为平铺根级叶子（无层级），每级按 sort 升序。</p>
     */
    List<SysPermission> listPermissionTree();
}