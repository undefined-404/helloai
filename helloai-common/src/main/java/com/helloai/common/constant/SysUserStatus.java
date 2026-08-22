package com.helloai.common.constant;

/**
 * 管理员用户（sys_user）状态，与 V1__init_all.sql 中 status 列 CHECK 约束对齐。
 */
public enum SysUserStatus {
    ACTIVE,
    DISABLED
}
