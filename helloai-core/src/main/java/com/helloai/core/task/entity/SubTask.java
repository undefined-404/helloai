package com.helloai.core.task.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.SubTaskStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sub_task")
public class SubTask extends BaseEntity {

    private Long taskId;
    private Long moduleId;
    private String title;
    private String deliverable;
    private String acceptance;
    private String priority;

    @TableField("status")
    private SubTaskStatus status;

    private Long assignedAgent;
    private String content;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> context;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> scoreFactors;

    private Integer compositeScore;
    private String scoreGrade;
    private OffsetDateTime deadline;
    private Integer reworkCount;
    private OffsetDateTime completedAt;

    @Version
    private Integer version;

    private Integer timeoutCount;

    /**
     * N11 阈值回退（V17 新增）：当前子任务已发生的"外部→LLM"回退次数。
     *
     * <p>每次 ExternalAgentFallbackTask 触发对当前子任务的重新分发，
     * 都会把该值 +1；可用于监控 / 限流（如回退 3 次后直接放弃或转人工）。</p>
     */
    private Integer externalFallbackCount;
}
