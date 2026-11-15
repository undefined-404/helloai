package com.helloai.core.task.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.FinalReportStatus;
import com.helloai.common.constant.TaskArchetype;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.shared.handler.PgJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "task", autoResultMap = true)
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
     * <p>Running Spec 已迁至独立表（{@code task_running_spec}/{@code task_execution_record}），本列不再写
     * {@code runningSpec} 键；存量由 {@code TaskRunningSpecDataMigrator} 搬迁。</p>
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
     * 交付物原型（拆解维度，`TD-1`/`V124`）：{@code BUILD}/{@code DOCUMENT}/{@code RESEARCH}/
     * {@code DATA}/{@code OPERATE}/{@code DECIDE}，默认 {@code BUILD}。
     *
     * <p>管「按什么维度拆」，与 {@code GRANULARITY}（拆多细）正交，拆解提示词按本值取对应拆分维度块（`TD-3`）。
     * 判定在澄清终稿产出时（`TD-4`）而非拆解时——拆解阶段模型只见 title+description 判不准（设计 §3.2）；
     * 判不出回落 {@code BUILD}，人工为权威输入。</p>
     */
    @TableField("task_archetype")
    private TaskArchetype taskArchetype;

    /**
     * 乐观锁版本号（`V114`，与 {@code SubTask}/{@code AgentExecutionRecord} 同口径）。
     *
     * <p>{@code TaskServiceImpl} 多处「{@code getById} 载入 → 改字段 → {@code updateById} 整实体」，
     * 缺本字段时两次读改写交错会用陈旧整行快照覆盖前者刚写入的字段（丢失更新且无报错）；加 {@code @Version}
     * 后由 {@code OptimisticLockerInnerInterceptor} 自动比较自增（规范 §15），冲突退化为 0 行受影响。
     * 契约纪律：属持久层并发控制，按 {@code CODE_STYLE §7.2/W10} 不得进入跨域投影
     * （{@code TaskView}/{@code TaskDraft}/{@code PlannerAgentRef}），否则消费方被迫复现 CAS。
     * {@code version} 为 null 时不触发 CAS（MyBatis-Plus 行为），新建后立即 updateById 与迁移前一致。</p>
     */
    @Version
    private Integer version;

    /** 归属用户 id（PA-1，V132）：建任务时继承澄清会话 owner，NULL 宽松并入。 */
    private Long ownerId;

    /**
     * 拆解租约到期时刻（`V146`）。
     *
     * <p>不用 {@code update_time} 判活：拆解线程调模型的十几分钟里一次库都不写，无法用心跳证明活着，
     * 用 update_time 判活等于「跑得比阈值久就判死」（dev provider 读超时与旧阈值相等，误杀必然）。
     * 改为进入 PLANNING 时写约定到期时刻、巡检只扫租约过期，与 {@code OrderExpireTask}/{@code TccFreezeTimeoutTask}
     * 扫 {@code expire_time} 同构（超时任务扫痕迹、不扫业务主体状态）。进入 {@code AWAITING_PLAN_CONFIRM} 后本字段无意义、不必清空。
     * 契约纪律：与 {@code version} 同属持久层并发控制，不进入 {@code TaskView} 等跨域投影。</p>
     */
    private OffsetDateTime decomposeLeaseUntil;

    /**
     * 当前（或最近一次）拆解的 attemptId（`V146`）。
     *
     * <p>必须在入口生成：幂等的本质是「同一件事有同一个名字」，异步段现生成会每次得新 UUID、
     * 暂存区 {@code batch_key} 每次新值，幂等形同虚设。与 {@link #decomposeLeaseUntil} 同一条 UPDATE 写入，
     * 避免「已 PLANNING 但 attemptId 为空」的窗口。</p>
     */
    private String decomposeAttemptId;

    /**
     * 执行期派生的父 SubTask id（`V148` / `ADR-008`）。
     *
     * <p><b>软引用、不建 FK</b>：派生链是可重派生的（失败后重新派生会换父锚），
     * 强外键会让「清理一次失败派生」变成级联约束问题。父 SubTask 置 DONE 的依据
     * 由派生 Task 完成态传播负责（见 {@code SubTaskCompletionListener}）。</p>
     */
    private Long parentSubTaskId;

    /**
     * 草案期派生的父 Task id（`V148` / `ADR-008`）。
     *
     * <p><b>为什么草案期不直接写 {@code parentSubTaskId}</b>：草案阶段父任务还没确认，
     * 对应的 SubTask 尚不存在。此时用 {@code (parent_task_id, parent_draft_code)} 作为
     * 父锚；父任务确认物化 SubTask 时回填 {@code parentSubTaskId} 并清空本列。</p>
     */
    private Long parentTaskId;

    /** 草案期派生的父草案 code（`V148`）；确认物化后清空，转到 {@link #parentSubTaskId}。 */
    private String parentDraftCode;

    /**
     * 根链 id（`V148`）：根任务 = 自身 id。
     *
     * <p><b>为什么冗余一列而不是靠递归查父</b>：看板与全链查询（报告聚合、成本归集、
     * 级联取消）都要回答「这棵树有多大」，逐层递归在大表上是 N 次往返。回填成
     * 「根 = 自身 id」后所有查询都能写成 {@code WHERE root_task_id = :id}，不再写
     * {@code COALESCE}。</p>
     */
    private Long rootTaskId;

    /**
     * 派生层级（`V148`）：根 0，全局 ≤2（最多 3 级）。
     *
     * <p>层级由本列表达，<b>不再从草案 code 段数推导</b>——派生是独立 Task，
     * 不存在「父 code 里嵌几段」这种结构信息。</p>
     */
    private Integer depth;

    /** 派生原因（`V148`）：{@code PLAN} = 草案期派生；{@code RESTRUCTURE} = 执行期重构派生。 */
    private String derivationReason;

    /** 同一父的第几次派生（`V148`，从 1 起）：区分合法重复派生与 bug。 */
    private Integer derivationRound;
}
