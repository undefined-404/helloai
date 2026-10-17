package com.helloai.core.task.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
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

    /** 最终整合报告正文（Markdown；null=尚未生成），由 Planner 收口后整合全部子任务产出。 */
    private String finalReport;

    /** 生成报告的 Planner Agent ID（软引用无 FK）。 */
    private Long finalReportAgentId;

    /** 报告生成时间。 */
    private OffsetDateTime finalReportTime;

    /** 报告生成状态（NONE/GENERATING/DONE/FAILED，与任务主状态解耦）。 */
    private FinalReportStatus finalReportStatus;

    /** 上一版最终整合报告正文（§12.1 单槽列：覆盖生成前落槽，回滚时与 current 互换；null=无上一版）。 */
    private String finalReportPrev;

    /** 上一版报告的生成 Planner Agent ID（软引用无 FK，随 prev 槽整体互换）。 */
    private Long finalReportPrevAgentId;

    /** 上一版报告生成时间。 */
    private OffsetDateTime finalReportPrevTime;

    /**
     * 任务扩展上下文（JSONB）。
     *
     * <p>历史用途：Task Running Spec 曾以 {@code runningSpec} 键存在本列（JSONB 过渡态）。
     * B4'（2026-10-02）双轨二选一后，Running Spec 已迁至独立表
     * （{@code task_running_spec} / {@code task_execution_record}），**不再写本键**；
     * 存量数据由 {@code TaskRunningSpecDataMigrator} 搬迁，迁完应清理本键。</p>
     */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private Map<String, Object> context;

    /**
     * 任务级 Agent 指定策略（JSONB，§6.58 P1）。
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
     * 任务要求的能力列表（JSONB[]，§6.58 P1）。
     *
     * <p>非空时执行者必须全部具备（AND 语义）；默认 {@code []} 不限制，
     * 与旧数据行为完全一致。</p>
     */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private List<String> requiredSkills;

    /**
     * 任务 SLA 分钟数（null=无时限）。
     *
     * <p>计划确认（confirmPlan）时按 {@code now + slaMinutes} 下发各子任务
     * {@code deadline}，外部 Agent 经 pullTasks 的 {@code deadline} 字段感知时限；
     * 子任务完成后 ImplicitScoreCalculator 依据 deadline 计算时间分。</p>
     */
    private Integer slaMinutes;

    /**
     * 任务优先级（N-006，C4-S1）：HIGH / MEDIUM / LOW，默认 MEDIUM。
     *
     * <p>sub_task 创建时未显式指定 priority 则继承本值（优先级继承三入口）；
     * 调度候选取数按优先级出队（C4 设计 §3.1/§3.2）。</p>
     */
    private String priority;

    /**
     * 乐观锁版本号（`V114` 新增列，与 {@code SubTask} / {@code AgentExecutionRecord} 同口径）。
     *
     * <p><b>存在理由</b>：{@code TaskServiceImpl} 的 {@code updateStatus} / {@code updatePriority} /
     * 配置更新 / 重新发布四处都是「{@code getById} 载入 → 改字段 → {@code updateById}(整实体)」。
     * 缺本字段时，两次读改写交错会让后写者用<b>陈旧的整行快照</b>覆盖前者刚写入的字段
     * （丢失更新），且全程无报错。加 {@code @Version} 后由
     * {@code OptimisticLockerInnerInterceptor} 自动完成 version 比较与自增（规范 §15），
     * 冲突更新退化为 0 行受影响而不再静默覆盖。</p>
     *
     * <p><b>契约纪律</b>：本字段属持久层并发控制，按 {@code CODE_STYLE §7.2 / W10}
     * <b>不得进入跨域契约</b>（{@code TaskView} / {@code TaskDraft} / {@code PlannerAgentRef}
     * 等投影一律不携带），否则消费方会被迫复现 CAS 语义。</p>
     *
     * <p><b>兼容性</b>：{@code version} 为 null 时不触发 CAS 条件（MyBatis-Plus 行为），
     * 故「新建实体后立即 {@code updateById}」的路径行为与迁移前一致。</p>
     */
    @Version
    private Integer version;
}
