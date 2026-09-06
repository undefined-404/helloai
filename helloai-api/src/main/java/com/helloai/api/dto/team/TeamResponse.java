package com.helloai.api.dto.team;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Team 响应（N-002，C2-S1）。
 */
@Data
public class TeamResponse {

    private Long id;
    private String name;
    private String description;
    /** DRAFT / ACTIVE / ARCHIVED。 */
    private String status;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
