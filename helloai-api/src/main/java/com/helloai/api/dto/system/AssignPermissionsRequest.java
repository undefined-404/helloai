package com.helloai.api.dto.system;

import lombok.Data;

import java.util.List;

/**
 * 角色绑定权限码请求（BASE-2.3 差异更新）。
 *
 * <p>{@code permissionIds} 为目标全量集合；{@code lastPermissionIds} 为绑定前快照（可选），
 * 服务层按差集批量增删——避免全量删插对大量权限码的无效写。缺省 lastPermissionIds 时
 * 回退全量删插（兼容旧调用方）。</p>
 */
@Data
public class AssignPermissionsRequest {

    private List<Long> permissionIds;

    /** 绑定前已绑定权限码快照（可选，用于差异计算） */
    private List<Long> lastPermissionIds;
}
