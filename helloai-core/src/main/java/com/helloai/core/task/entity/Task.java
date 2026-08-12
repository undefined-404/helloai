package com.helloai.core.task.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.FinalReportStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.shared.handler.PgJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("task")
public class Task extends BaseEntity {

    private String title;
    private String description;
    private TaskStatus status;

    /** 最终整合报告正文（V32，Markdown；null=尚未生成），由 Planner 收口后整合全部子任务产出。 */
    private String finalReport;

    /** 生成报告的 Planner Agent ID（V32，软引用无 FK）。 */
    private Long finalReportAgentId;

    /** 报告生成时间（V32）。 */
    private OffsetDateTime finalReportTime;

    /** 报告生成状态（V41，NONE/GENERATING/DONE/FAILED，与任务主状态解耦）。 */
    private FinalReportStatus finalReportStatus;

    /**
     * 任务扩展上下文（V35 新增 JSONB）。
     *
     * <p>当前用途：Task Running Spec（Phase A JSONB 过渡态）——
     * {@code runningSpec.baseline} / {@code runningSpec.executionRecords} /
     * {@code runningSpec.contextSummary}。</p>
     */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private Map<String, Object> context;

    /**
     * 任务级 Agent 指定策略（V47 新增 JSONB，§6.58 P1）。
     *
     * <p>键说明（解析统一走 {@code TaskAgentPolicy} 静态工具类）：
     * <ul>
     *   <li>{@code plannerAgentId}：指定拆解/澄清 Planner（失效回退自动选择）；</li>
     *   <li>{@code executorAgentIds[]}：执行者白名单（为空=不限定）；</li>
     *   <li>{@code reviewerAgentId}：指定自动核验 Reviewer（失效回退自动选择）；</li>
     *   <li>{@code fallbackPolicy}：AUTO / RESTRICTED / NONE（N11 回退约束）；</li>
     *   <li>{@code difficulty}：LOW / MEDIUM / HIGH（HIGH 视为禁止 N11 自动回退）。</li>
     * </ul>
     * 默认 {@code {}}：旧数据行为与现状完全一致。</p>
     */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private Map<String, Object> agentPolicy;

    /**
     * 任务要求的能力列表（V47 新增 JSONB[]，§6.58 P1）。
     *
     * <p>非空时执行者必须全部具备（AND 语义）；默认 {@code []} 不限制，
     * 与旧数据行为完全一致。</p>
     */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private List<String> requiredSkills;

    /**
     * 任务 SLA 分钟数（A0-7 新增，V48；null=无时限）。
     *
     * <p>计划确认（confirmPlan）时按 {@code now + slaMinutes} 下发各子任务
     * {@code deadline}，外部 Agent 经 pullTasks 的 {@code deadline} 字段感知时限；
     * 子任务完成后 ImplicitScoreCalculator 依据 deadline 计算时间分。</p>
     */
    private Integer slaMinutes;
}
