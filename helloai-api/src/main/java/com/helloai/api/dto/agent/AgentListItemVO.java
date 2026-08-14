package com.helloai.api.dto.agent;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
public class AgentListItemVO {
    private Long id;
    private String name;
    private AgentRole role;
    /** 接入类型：前端据此隐藏内部 LLM Agent 的接入内容入口 */
    private AgentAccessType accessType;

    /** V52: 内部 LLM Agent 绑定的 provider:model（编辑弹窗技能区三段式渲染数据源），外部 Agent 为 null */
    private String modelType;
    private String apiKey;
    private String description;
    private AgentStatus status;
    private Integer totalScore;
    private Integer rank;

    /** V47/A2: 能力声明列表（shell / docker / code-review / web-search 等，任务 required_skills 匹配用，前端编辑弹窗回显） */
    private List<String> skills;

    /* workload */
    private int assignedCount;
    private int inProgressCount;
    private int doneCount;
    private int blockedCount;
    private int reviewCount;

    /* timeline */
    private OffsetDateTime lastRequestAt;
    private OffsetDateTime lastActivityAt;
    private OffsetDateTime createdAt;
}
