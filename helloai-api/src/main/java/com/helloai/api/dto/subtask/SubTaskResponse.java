package com.helloai.api.dto.subtask;

import com.helloai.common.constant.SubTaskStatus;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
public class SubTaskResponse {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;
    /** 主任务标题冗余字段（列表/详情展示归属任务用，由 Controller 批量回填） */
    private String taskTitle;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long moduleId;
    private String title;
    private String deliverable;
    private String acceptance;
    private String priority;
    private SubTaskStatus status;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long assignedAgent;
    /** Agent 名称冗余字段（列表展示负责人用，由 Controller 批量回填） */
    private String assignedAgentName;
    private String content;
    private Integer reworkCount;
    /** 执行上下文（JSONB）：含人工介入标记 manualIntervention，§6.52 前端人工介入面板判定依赖此字段 */
    private Map<String, Object> context;
    /** 依赖的子任务 id 列表（V27 新增，同 Task 内；空列表=无依赖） */
    @JsonSerialize(contentUsing = ToStringSerializer.class)
    private List<Long> dependsOn;
    private OffsetDateTime deadline;
    private OffsetDateTime completedAt;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
