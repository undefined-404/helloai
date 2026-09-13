package com.helloai.api.dto.system;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增用户请求（BASE-4.5，管理员建号）。
 */
@Data
public class SysUserCreateRequest {

    @NotBlank(message = "请输入用户名")
    @Size(min = 2, max = 64, message = "用户名长度需在 2-64 位之间")
    private String username;

    @NotBlank(message = "请输入密码")
    @Size(min = 6, max = 64, message = "密码长度需在 6-64 位之间")
    private String password;

    /** 昵称（可空） */
    private String nickname;

    /** 初始角色码（如 SUPER_ADMIN / ADMIN / NORMAL_USER / GUEST）；为空时取默认 ADMIN */
    private String roleCode;

    /** 备注（可空） */
    private String remark;
}
