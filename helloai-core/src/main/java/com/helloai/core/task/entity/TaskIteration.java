package com.helloai.core.task.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

/**
 * 任务级执行迭代记录（V42）。
 *
 * <p>每轮执行（含 rework）一条记录，在 Planner 整合报告生成时一次性回填。
 * 通过 parent_task_id 自引用支持"超大任务 → 主任务 → 子任务"层级；
 * task_code 用于前端时序图展示序号（#1, #2 ...）。</p>
 *
 * <p>与现有表的关系：
 * <ul>
 *   <li>{@code task_id} 关联 {@code task.id}（顶层任务）</li>
 *   <li>不替代 {@code sub_task}（运行时工作台），互补为收口快照</li>
 *   <li>不替代 {@code task_timeline}（事件审计）、{@code task_execution_record}（执行回填）</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("task_iteration")
public class TaskIteration extends BaseEntity {

    /** 关联顶层 task.id */
    private Long taskId;

    /** 任务序号（前端时序图展示，如 #1, #2） */
    private String taskCode;

    /** 任务名称（冗余，方便查询展示） */
    private String taskName;

    /** 任务类型：DEVELOPMENT / TESTING / PLANNING / OTHER */
    private String taskType;

    /** 上级迭代记录 ID（自引用，NULL=顶级任务） */
    private Long parentTaskId;

    /** 前置依赖的迭代记录 ID 数组（JSONB） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Long> dependsOn;

    /** 迭代轮次（同一 task 的 rework 递增） */
    private Integer roundNum;

    /** 前置任务结果汇总 */
    private String prevTaskResult;

    /** 本轮任务要求 */
    private String currentRequirement;

    /** 执行摘要（从 EXECUTION_RECORD SUMMARY 解析，≤200 字） */
    private String outputSummary;

    /** 上次生成结果（rework 时携带） */
    private String lastResult;

    /** 全部历史驳回意见 [{round, comment, issues, score}]（JSONB） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> rejectionHistory;

    /** 本轮 LLM 返回完整结果 */
    private String llmResponse;

    /** 审核结果：PASSED / REJECTED（NULL=未审核） */
    private String reviewResult;

    /** 执行 Agent 模型注册名称 */
    private String executorAgent;
}
