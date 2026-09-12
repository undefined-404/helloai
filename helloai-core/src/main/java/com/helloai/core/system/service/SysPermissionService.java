package com.helloai.core.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.system.entity.SysPermission;

/**
 * 系统权限/菜单管理服务（RBAC 写侧，BASE-2.1）。
 *
 * <p>负责权限码 / 菜单树 CRUD。type=MENU 即菜单项（parent_id 承载层级），type=API 为
 * 接口动作码（无层级）。删除守卫：存在子节点 / 已被角色引用时拒绝，避免破坏授权基线。
 * 写操作走 {@code @Transactional}。</p>
 */
public interface SysPermissionService extends IService<SysPermission> {

    /**
     * 创建权限码 / 菜单。
     *
     * @param req code / name / type 必填；MENU 可带 parentId / path / icon / component
     * @throws BizException code 重复、type 非法、父节点缺失或非 MENU 时
     */
    SysPermission createPermission(SysPermission req);

    /**
     * 更新权限码 / 菜单（code 为唯一标识不随编辑变更）。
     *
     * @param req 仅非 null 字段更新；type 变更需重新校验 parent 约束
     */
    SysPermission updatePermission(Long id, SysPermission req);

    /**
     * 删除权限码 / 菜单。
     *
     * @throws BizException 存在子节点（MENU）或被角色绑定引用时拒绝
     */
    void deletePermission(Long id);
}
