package com.helloai.api.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.helloai.api.dto.system.AssignUserDepartsRequest;
import com.helloai.api.dto.system.AssignUserRolesRequest;
import com.helloai.api.dto.system.ResetPasswordRequest;
import com.helloai.api.dto.system.SysUserItem;
import com.helloai.api.dto.system.SysUserUpdateRequest;
import com.helloai.common.base.R;
import com.helloai.core.system.entity.SysUser;
import com.helloai.core.system.service.SysDepartService;
import com.helloai.core.system.service.SysRoleService;
import com.helloai.core.system.service.SysUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 用户管理接口（RBAC：用户分页 + 用户-角色 / 部门分配）。
 *
 * <p>位于 {@code /api/admin/**} 路径下，由 AdminOnlyInterceptor 强制 admin 身份，
 * 并由动作级权限码鉴权（BASE-1.6）：user:view / user:edit / user:assign-role /
 * user:reset-pwd；组织归属分配（部门）复用 user:edit。</p>
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class SysUserRoleController {

    private final SysRoleService sysRoleService;
    private final SysUserService sysUserService;
    private final SysDepartService sysDepartService;

    /**
     * 用户分页列表（附带角色码 / 部门）。
     */
    @SaCheckPermission("user:view")
    @GetMapping("/page")
    public R<IPage<SysUserItem>> page(@RequestParam(defaultValue = "1") long page,
                                      @RequestParam(defaultValue = "10") long size,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) Long departId) {
        // 当前登录用户 ID：用于 BASE-3.3 数据权限（user:view 规则）过滤可见范围
        Long currentUserId = Long.valueOf(StpUtil.getLoginId().toString());
        IPage<SysUser> p = sysUserService.pageUsers(keyword, page, size, departId, currentUserId);
        List<Long> userIds = p.getRecords().stream().map(SysUser::getId).toList();
        Map<Long, List<String>> roleCodesByUser = sysRoleService.listRoleCodesByUserIds(userIds);
        Map<Long, List<Long>> departIdsByUser = sysDepartService.listDepartIdsByUserIds(userIds);
        Map<Long, List<String>> departNamesByUser = sysDepartService.listDepartNamesByUserIds(userIds);
        return R.ok(p.convert(u -> {
            SysUserItem item = new SysUserItem();
            item.setId(u.getId());
            item.setUsername(u.getUsername());
            item.setNickname(u.getNickname());
            item.setEmail(u.getEmail());
            item.setPhone(u.getPhone());
            item.setStatus(u.getStatus());
            item.setRole(u.getRole());
            item.setLastLoginTime(u.getLastLoginTime());
            item.setLastLoginIp(u.getLastLoginIp());
            item.setRoleCodes(roleCodesByUser.getOrDefault(u.getId(), List.of()));
            item.setDepartIds(departIdsByUser.getOrDefault(u.getId(), List.of()));
            item.setDepartNames(departNamesByUser.getOrDefault(u.getId(), List.of()));
            return item;
        }));
    }

    @SaCheckPermission("user:view")
    @GetMapping("/{userId}/roles")
    public R<List<Long>> roles(@PathVariable("userId") Long userId) {
        return R.ok(sysRoleService.listRoleIdsByUser(userId));
    }

    @SaCheckPermission("user:assign-role")
    @PutMapping("/{userId}/roles")
    public R<Void> assignRoles(@PathVariable("userId") Long userId,
                               @RequestBody AssignUserRolesRequest req) {
        sysRoleService.assignUserRoles(userId, req.getRoleIds());
        return R.ok();
    }

    /** 查询用户已关联部门 id（BASE-3.2） */
    @SaCheckPermission("user:view")
    @GetMapping("/{userId}/departs")
    public R<List<Long>> departs(@PathVariable("userId") Long userId) {
        return R.ok(sysDepartService.listDepartIdsByUser(userId));
    }

    /** 分配用户部门（BASE-3.2，全量覆盖） */
    @SaCheckPermission("user:edit")
    @PutMapping("/{userId}/departs")
    public R<Void> assignDeparts(@PathVariable("userId") Long userId,
                                 @RequestBody AssignUserDepartsRequest req) {
        sysDepartService.assignUserDeparts(userId, req.getDepartIds());
        return R.ok();
    }

    @SaCheckPermission("user:edit")
    @PutMapping("/{userId}")
    public R<Void> update(@PathVariable("userId") Long userId,
                          @RequestBody SysUserUpdateRequest req) {
        sysUserService.updateUser(userId, req.getNickname(), req.getEmail(), req.getPhone(), req.getStatus());
        return R.ok();
    }

    @SaCheckPermission("user:reset-pwd")
    @PutMapping("/{userId}/password")
    public R<Void> resetPassword(@PathVariable("userId") Long userId,
                                 @Valid @RequestBody ResetPasswordRequest req) {
        sysUserService.resetPassword(userId, req.getNewPassword());
        return R.ok();
    }
}
