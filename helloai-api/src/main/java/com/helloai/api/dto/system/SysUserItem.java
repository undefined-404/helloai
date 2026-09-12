package com.helloai.api.dto.system;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 用户管理列表项（含角色码，供展示）。
 */
@Data
public class SysUserItem {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String username;
    private String nickname;
    private String email;
    private String phone;
    private String status;
    /** 存量 sys_user.role 单字段（兼容展示，新分配以关联表 sys_user_role 为准） */
    private String role;
    private OffsetDateTime lastLoginTime;
    private String lastLoginIp;
    /** 已分配角色码列表（如 [SUPER_ADMIN] / [ADMIN]） */
    private List<String> roleCodes;

    /** 已关联部门 id 列表（BASE-3.2，用于分配回显） */
    private List<Long> departIds;

    /** 已关联部门名称列表（BASE-3.2，用于展示） */
    private List<String> departNames;
}
