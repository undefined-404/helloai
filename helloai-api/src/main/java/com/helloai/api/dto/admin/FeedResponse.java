package com.helloai.api.dto.admin;

import lombok.Data;
import java.time.OffsetDateTime;

@Data
public class FeedResponse {
    private Long id;
    private Long agentId;
    private String agentName;
    private String action;
    private String level;
    private String source;
    private OffsetDateTime createTime;
}
