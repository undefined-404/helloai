package com.helloai.api.dto.system;

import lombok.Data;

import java.util.List;

/**
 * 用户分配角色请求（roleIds 全量覆盖）。
 */
@Data
public class AssignUserRolesRequest {

    private List<Long> roleIds;
}