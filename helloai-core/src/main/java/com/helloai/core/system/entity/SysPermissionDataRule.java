package com.helloai.core.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 权限数据规则（数据权限，受控枚举，BASE-3.3）。
 *
 * <p>一个权限码一条规则（唯一索引）。{@code ruleType} 为白名单枚举：
 * {@code ALL}（全部）/ {@code DEPT}（本部门）/ {@code DEPT_AND_CHILD}（本部门及下级）/
 * {@code CUSTOM}（自定义部门，{@code ruleValue} 为部门 ID 逗号分隔）。
 * 服务层按枚举解释为部门范围，不拼接任意 SQL。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_permission_data_rule")
public class SysPermissionDataRule extends BaseEntity {

    private Long permissionId;

    /** 规则类型：ALL / DEPT / DEPT_AND_CHILD / CUSTOM */
    private String ruleType;

    /** CUSTOM 时的部门 ID 逗号分隔（如 "10,11,12"） */
    private String ruleValue;
}
