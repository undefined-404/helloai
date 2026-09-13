package com.helloai.api.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 自助注册请求（BASE-4.5）。
 *
 * <p>仅当 {@code sys_config.auth.register.enabled} 开启时可用；注册账号默认绑定
 * {@code GUEST} 角色（最小权限，只读）。</p>
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "请输入用户名")
    @Size(min = 2, max = 64, message = "用户名长度需在 2-64 位之间")
    private String username;

    @NotBlank(message = "请输入密码")
    @Size(min = 6, max = 64, message = "密码长度需在 6-64 位之间")
    private String password;

    /** 昵称（可空） */
    private String nickname;
}
