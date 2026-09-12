package com.helloai.api.dto.system;

import lombok.Data;

/**
 * 权限数据规则投影（BASE-3.3）。
 */
@Data
public class DataRuleVO {

    private Long permissionId;

    /** 规则类型：ALL / DEPT / DEPT_AND_CHILD / CUSTOM */
    private String ruleType;

    /** CUSTOM 时的部门 ID 逗号分隔 */
    private String ruleValue;

    public static DataRuleVO of(Long permissionId, String ruleType, String ruleValue) {
        DataRuleVO vo = new DataRuleVO();
        vo.setPermissionId(permissionId);
        vo.setRuleType(ruleType);
        vo.setRuleValue(ruleValue);
        return vo;
    }
}
