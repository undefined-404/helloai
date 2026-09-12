package com.helloai.api.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 登录 / 当前用户信息响应。
 *
 * <p>admin 登录返回 {@code permissions}（权限码）与 {@code roles}（角色码），供前端
 * 按权限码过滤菜单显隐；agent 通道不涉及 RBAC 权限码，两者返回空列表。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private String token;
    private String type;       // "admin" 或 "agent"
    private String displayName;
    private String role;       // "SUPER_ADMIN" / "ADMIN" / ...
    private List<String> permissions;
    private List<String> roles;
}
