package com.helloai.core.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.system.entity.SysRole;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 系统角色服务（RBAC 管理与授权）。
 *
 * <p>负责角色 CRUD、角色-权限码绑定、用户-角色分配。写操作走 {@code @Transactional}，
 * 关联表（sys_role_permission / sys_user_role）先删后插保持幂等。</p>
 */
public interface SysRoleService extends IService<SysRole> {

    /**
     * 创建角色
     */
    SysRole createRole(String code, String name, String description, String status, Integer sort);

    /**
     * 更新角色（不含 code；code 为角色唯一标识不随编辑变更）
     */
    SysRole updateRole(Long id, String name, String description, String status, Integer sort);

    /**
     * 删除角色（内置角色不可删；删除时级联解绑权限关联）
     */
    void deleteRole(Long id);

    /**
     * 查询角色已绑定权限码 id 列表
     */
    List<Long> listPermissionIds(Long roleId);

    /**
     * 绑定角色权限码（先删后插，全量覆盖）。
     */
    void assignPermissions(Long roleId, List<Long> permissionIds);

    /**
     * 绑定角色权限码（差异更新，BASE-2.3）。
     *
     * @param lastPermissionIds 绑定前已绑定权限码快照；null 时回退全量删插
     */
    void assignPermissions(Long roleId, List<Long> permissionIds, List<Long> lastPermissionIds);

    /**
     * 查询用户已分配角色 id 列表
     */
    List<Long> listRoleIdsByUser(Long userId);

    /**
     * 分配用户角色（先删后插，全量覆盖）
     */
    void assignUserRoles(Long userId, List<Long> roleIds);

    /**
     * 批量查询用户角色码（供用户管理列表展示；空入参返回空 Map）。
     */
    Map<Long, List<String>> listRoleCodesByUserIds(Collection<Long> userIds);
}