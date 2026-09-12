package com.helloai.api.dto.system;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 部门创建/更新请求（BASE-3.2）。
 */
@Data
public class SysDepartSaveRequest {

    @NotBlank(message = "部门名称不能为空")
    private String name;

    /** 父部门 ID（NULL=顶级部门） */
    private Long parentId;

    private Integer sort;
    private String status;
    private String description;
}
