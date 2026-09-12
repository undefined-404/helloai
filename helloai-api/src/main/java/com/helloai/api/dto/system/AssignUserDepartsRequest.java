package com.helloai.api.dto.system;

import lombok.Data;

import java.util.List;

/**
 * 用户-部门分配请求（BASE-3.2，departIds 全量覆盖）。
 */
@Data
public class AssignUserDepartsRequest {

    private List<Long> departIds;
}
