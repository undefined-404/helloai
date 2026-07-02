package com.helloai.api.dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank(message = "登录类型不能为空")
    private String type;   // "admin" 或 "agent"

    private String username;  // 管理员登录：用户名

    @NotBlank(message = "凭证不能为空")
    private String credential;  // 管理员：密码 / Agent：API Key
}
