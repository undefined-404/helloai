package com.helloai.api.dto.system;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 权限数据规则保存请求（BASE-3.3）。
 *
 * <p>ruleType 白名单：ALL（全部）/ DEPT（本部门）/ DEPT_AND_CHILD（本部门及下级）/
 * CUSTOM（自定义部门，需提供 ruleValue 部门 ID 逗号分隔）。</p>
 */
@Data
public class DataRuleSaveRequest {

    @NotBlank(message = "规则类型不能为空")
    private String ruleType;

    /** CUSTOM 时的部门 ID 逗号分隔（如 "10,11"） */
    private String ruleValue;
}
