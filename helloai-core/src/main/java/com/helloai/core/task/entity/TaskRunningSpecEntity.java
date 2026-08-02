package com.helloai.core.task.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.helloai.common.base.BaseEntity;
import com.helloai.core.shared.handler.PgJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * Task Running Spec 持久化实体（Phase B 目标态）。
 *
 * <p>对应 {@code task_running_spec} 表，每个 task 一条记录。
 * 与领域对象 {@code TaskRunningSpec}（{@code core.task.spec}）的区别：
 * 领域对象面向调用方、字段语义高阶（baseline/executionRecords/contextSummary），
 * 本实体面向 MyBatis-Plus 持久化，存储原始字段。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "task_running_spec", autoResultMap = true)
public class TaskRunningSpecEntity extends BaseEntity {

    /** 主任务 ID（一对一约束）。 */
    private Long taskId;

    /** 版本号（当前固定为 1，保留字段便于未来演进）。 */
    @Version
    private Integer version;

    /**
     * Baseline（JSONB）：Planner 写入的全局规格，
     * 内部为 {@code {goal, constraints, raw, createdBy, createdAt}}。
     */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private Map<String, Object> baseline;

    /**
     * ContextSummary（TEXT）：从所有 ExecutionRecords 自动编译的连贯段落，
     * 注入到下游 Executor Prompt 头部。
     */
    private String contextSummary;
}