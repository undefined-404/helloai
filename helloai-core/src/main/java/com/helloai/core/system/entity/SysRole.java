package com.helloai.core.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统角色（RBAC）。
 *
 * <p>角色是权限码的集合载体；用户经 {@code sys_user_role} 多对多关联角色，
 * 角色经 {@code sys_role_permission} 多对多关联权限码。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role")
public class SysRole extends BaseEntity {

    private String code;
    private String name;
    private String description;
    private String status;
    private Integer sort;
}