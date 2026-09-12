package com.helloai.api.dto.system;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 角色创建/更新请求。创建时 code 必填；更新时代码不参与（取路径 id）。
 */
@Data
public class SysRoleSaveRequest {

    @NotBlank(message = "角色编码不能为空")
    private String code;

    @NotBlank(message = "角色名称不能为空")
    private String name;

    private String description;
    private String status;
    private Integer sort;
}