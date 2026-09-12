package com.helloai.api.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.helloai.api.dto.system.AssignPermissionsRequest;
import com.helloai.api.dto.system.SysRoleSaveRequest;
import com.helloai.common.base.R;
import com.helloai.core.system.entity.SysRole;
import com.helloai.core.system.service.SysRoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 角色管理接口（RBAC：角色 CRUD + 角色-权限码绑定）。
 *
 * <p>位于 {@code /api/admin/**} 路径下，由 AdminOnlyInterceptor 强制 admin 身份；
 * 动作级权限码鉴权（BASE-1.6）：role:view / role:add / role:edit / role:delete /
 * role:assign-perm。权限绑定支持差异更新（BASE-2.3）。</p>
 */
@RestController
@RequestMapping("/api/admin/roles")
@RequiredArgsConstructor
public class SysRoleController {

    private final SysRoleService sysRoleService;

    @SaCheckPermission("role:view")
    @GetMapping
    public R<List<SysRole>> list() {
        return R.ok(sysRoleService.list());
    }

    @SaCheckPermission("role:add")
    @PostMapping
    public R<SysRole> create(@Valid @RequestBody SysRoleSaveRequest req) {
        return R.ok(sysRoleService.createRole(req.getCode(), req.getName(), req.getDescription(),
                req.getStatus(), req.getSort()));
    }

    @SaCheckPermission("role:edit")
    @PutMapping("/{id}")
    public R<SysRole> update(@PathVariable("id") Long id, @RequestBody SysRoleSaveRequest req) {
        return R.ok(sysRoleService.updateRole(id, req.getName(), req.getDescription(),
                req.getStatus(), req.getSort()));
    }

    @SaCheckPermission("role:delete")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable("id") Long id) {
        sysRoleService.deleteRole(id);
        return R.ok();
    }

    @SaCheckPermission("role:view")
    @GetMapping("/{id}/permissions")
    public R<List<Long>> permissions(@PathVariable("id") Long id) {
        return R.ok(sysRoleService.listPermissionIds(id));
    }

    @SaCheckPermission("role:assign-perm")
    @PutMapping("/{id}/permissions")
    public R<Void> assignPermissions(@PathVariable("id") Long id,
                                     @RequestBody AssignPermissionsRequest req) {
        sysRoleService.assignPermissions(id, req.getPermissionIds(), req.getLastPermissionIds());
        return R.ok();
    }
}