package com.helloai.api.dto.system;

import lombok.Data;

/**
 * 用户信息更新请求（字段为 null 时不更新）。
 */
@Data
public class SysUserUpdateRequest {

    private String nickname;
    private String email;
    private String phone;
    private String status;
}
