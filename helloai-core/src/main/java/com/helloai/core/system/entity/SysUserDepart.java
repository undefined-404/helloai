package com.helloai.core.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户-部门关联（RBAC，多对多，BASE-3.2）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user_depart")
public class SysUserDepart extends BaseEntity {

    private Long userId;
    private Long departId;
}
