package com.helloai.api.dto.team;

import lombok.Data;

/**
 * Team 创建/更新请求（N-002，C2-S1）。
 */
@Data
public class TeamRequest {

    /** Team 名称（必填，唯一）。 */
    private String name;

    private String description;
}
