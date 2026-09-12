package com.helloai.core.system.auth;

import cn.dev33.satoken.stp.StpInterface;
import com.helloai.core.system.service.SysPermissionQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sa-Token 权限/角色查询实现（RBAC 授权内核）。
 *
 * <p>{@code @SaCheckPermission} / {@code @SaCheckRole} 注解经此实现取当前登录账号的
 * 权限码与角色码。当前仅 admin（浏览器用户）建立 Sa-Token 会话，loginId 即 {@code sys_user.id}；
 * agent（外部 CLI Agent）走 API Key / MCP 契约，不进入本体系。</p>
 */
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final SysPermissionQueryService sysPermissionQueryService;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        if (loginId == null) {
            return List.of();
        }
        return sysPermissionQueryService.listPermissionCode(Long.valueOf(loginId.toString()));
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        if (loginId == null) {
            return List.of();
        }
        return sysPermissionQueryService.listRoleCode(Long.valueOf(loginId.toString()));
    }
}