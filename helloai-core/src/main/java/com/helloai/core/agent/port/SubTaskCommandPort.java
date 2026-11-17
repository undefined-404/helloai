package com.helloai.core.agent.port;

import com.helloai.common.constant.SubTaskStatus;

import java.util.Map;

/**
 * 子任务命令端口（agent 域消费，task 域实现）。
 *
 * <p>端口归属（{@code CODE_STYLE §7.2}）：同 {@link SubTaskQueryPort}——消费方 agent 低于提供方 task，
 * 端口落消费方 {@code agent.port}、适配器落 task 域，实现侧 {@code task→agent} 顺向合法。</p>
 *
 * <p><b>不透明命令 vs 原子命令的划界</b>（备忘录 §16.8 / W7）：判据不是「有没有判定」，而是<b>判定产出什么</b>——
 * 产出<b>状态变更决策</b>（该不该推进、推进到哪）⇒ 属提供方领域规则，「判定 + 写」整体搬进 task 域
 * （{@link #startIfNeeded}；留消费方等于把状态机搬进 agent，日后判定需加锁读时会撕开事务边界）；
 * 产出<b>对外响应契约</b>（MCP {@code reason} 码 / HTTP 语义 / 协议分支）⇒ 属消费方协议适配层，判定留消费方、
 * 提供方只给原子命令（{@link #start} / {@link #claimAtomic} / {@link #block}），否则领域被各调用方响应格式污染。
 * 反例边界：纯无判定的副作用写（收件箱通知）才走事件，不可与上述混用。</p>
 *
 * <p>实现见 {@code task.service.impl.SubTaskCommandPortAdapter}。</p>
 */
public interface SubTaskCommandPort {

    /**
     * 子任务状态按需推进到 {@code IN_PROGRESS}（消费侧幂等前置）。
     *
     * <p>判定与写入<b>整体在 task 域完成</b>：{@code IN_PROGRESS} 幂等跳过；
     * {@code ASSIGNED / REWORK / PAUSED} 推进为 {@code IN_PROGRESS}；其余状态（含 {@code null}）
     * 抛 {@code BizException}（不允许执行）。异常语义与原 agent 侧实现逐字一致。</p>
     *
     * @param subTaskId 子任务 ID
     * @param status    调用方观测到的当前状态（仅作判定输入，提供方不据此回写）
     */
    void startIfNeeded(Long subTaskId, SubTaskStatus status);

    /**
     * 级联删除前解绑：将指定 Agent 名下的子任务 {@code assigned_agent_id} 置空。
     *
     * <p>调用方（agent 域级联删除）保持自身事务，提供方以 REQUIRED 传播加入，
     * 属同事务同步写 —— 不能用事件替代。</p>
     *
     * @param agentId Agent ID
     */
    void unlinkByAssignedAgent(Long agentId);

    /**
     * 子任务状态推进到 {@code IN_PROGRESS}（<b>原子命令，无消费侧判定</b>，W7 新增）。
     *
     * <p>不做状态白名单裁决，直接委派 {@code SubTaskService#start}——合法性由提供方
     * {@code SubTaskStateMachine} + {@code @Version} 乐观锁保障。调用方（MCP 工具）自带前置校验分支
     * 以产出对外 reason 码，属消费方协议适配职责（见接口 javadoc 划界）。</p>
     *
     * @param subTaskId 子任务 ID
     */
    void start(Long subTaskId);

    /**
     * 子任务状态推进到 {@code REVIEW}（<b>原子命令，无消费侧判定</b>，W10 新增）。
     *
     * <p>直接委派 {@code SubTaskService#submit}（内部 {@code changeStatus(subTaskId, REVIEW, null)}），
     * 合法性（当前须为 {@code IN_PROGRESS}）由提供方 {@code SubTaskStateMachine} 保障，消费方不做前置裁决；
     * 执行结果回写链路在「执行成功」分支调用，与 {@link #start} 同族。</p>
     *
     * @param subTaskId 子任务 ID
     */
    void submit(Long subTaskId);

    /**
     * 子任务状态推进到 {@code CANCELLED}（<b>原子命令，无消费侧判定</b>，ADR-009 A-1 新增）。
     *
     * <p>直接委派 {@code SubTaskService#cancel}（内部经 {@code SubTaskStateMachine} 校验合法性 +
     * {@code changeStatus} 单一写入入口，级联取消 / attempt 收口 / outbox / inbox / 交接通知全在既有事件序内）。
     * 调用方（MCP 工具）自带「归属 + 非终态」前置校验以产出对外 {@code BizException(400)} 语义，
     * 属消费方协议适配职责（见接口 javadoc 划界）；本命令不复刻该判定。</p>
     *
     * @param subTaskId 子任务 ID
     */
    void cancel(Long subTaskId);

    /**
     * 原子认领：条件更新 {@code WHERE status='PENDING' AND (assigned_agent IS NULL OR = agentId)}。
     *
     * <p><b>不透明原子命令</b>：互斥条件写在 SQL 里（状态机的一部分），消费方无从复现，
     * 故整体委派提供方。</p>
     *
     * @param subTaskId 子任务 ID
     * @param agentId   认领方 Agent ID
     * @return 占位更新成功返回 {@code true}；已被他人抢走 / 状态已变返回 {@code false}
     */
    boolean claimAtomic(Long subTaskId, Long agentId);

    /**
     * 上报子任务阻塞（{@code BLOCKED}），并提供方内部触发 PLANNER 排障通知。
     *
     * <p>副作用（通知）与写入整体在提供方，消费方只表达「我要报阻塞」的意图。</p>
     *
     * <p><b>两个入参均可为 {@code null}</b>：{@code null} 时提供方仅记录 {@code blockedAt}
     * 时间戳并落 {@code sub_task_report_blocked} 时间线（{@code reason} 记空串）。
     * 故 {@code block(subTaskId, null, null)} <b>逐字等价于</b> {@code SubTaskService#block(Long)}——
     * 执行结果回写链路的「执行失败」分支即以此形式复用本方法，无需另开重载。</p>
     *
     * @param subTaskId 子任务 ID
     * @param reason    阻塞原因；可为 {@code null}（仅记录时间戳）
     * @param agentId   上报 Agent ID；可为 {@code null}
     */
    void block(Long subTaskId, String reason, Long agentId);

    /**
     * 把 PENDING 子任务分配给指定 Agent（<b>不透明命令</b>，W8 新增）。
     *
     * <p>「判定 + 写」整体在 task 域：{@code SubTaskService#assignNext} 内含 PENDING 前置校验 +
     * <b>Agent 行锁（FOR UPDATE）串行化并发派发</b> + 锁内并发额度判定 + {@code changeStatus(ASSIGNED)} 落库。
     * 互斥与额度口径都是提供方领域规则，消费方（{@code ResilientDispatcher}）只表达派给谁的意图、不做裁决。</p>
     *
     * <p>失败语义：状态非 PENDING 抛 {@code BizException}；Agent 并发额度已满抛
     * {@code AgentUnavailableException}（<b>不计入熔断统计</b>，由调用方 fallback 换人）。</p>
     *
     * @param agentId   目标 Agent ID
     * @param subTaskId 待分配的子任务 ID
     */
    void assignNext(Long agentId, Long subTaskId);

    /**
     * 写入「需人工介入」标记（best-effort 降级写，W8 新增）。
     *
     * <p>在子任务 {@code context} 打 {@code manualIntervention} 标记并落 timeline。
     * 提供方按 CODE_STYLE §7.1「单语句原子写豁免」口径<b>不加事务</b>、
     * 整段 try-catch 降级（失败仅告警、不影响主链路）——该降级语义属提供方实现细节，
     * 消费方无需感知，故作为不透明命令暴露。</p>
     *
     * @param subTaskId 子任务 ID
     * @param reason    介入原因码（如 {@code dispatch_skip_execution_dense}）
     * @param extra     附加审计字段；可为 {@code null}
     */
    void markManualIntervention(Long subTaskId, String reason, Map<String, Object> extra);

    /**
     * 整体覆写子任务 {@code context}（<b>不透明命令</b>，W10 新增）。
     *
     * <p><b>为什么整体覆写而非「读快照 + 消费方改 + 写回」</b>：后者契约须带 {@code version} 让消费方复现
     * MyBatis-Plus CAS，那是提供方存储实现细节、不该外泄。故收为「调用方给出目标 {@code context}，
     * 提供方自行取最新行、整体覆写」。</p>
     *
     * <p><b>语义</b>：适配器内 {@code getById → setContext → updateById} 单实体原子写；子任务不存在则静默返回
     * （同 {@code updateById} 更新 0 行不报错）。<b>刻意取最新行再写</b>：消费方快照可能更早读取，
     * 携带陈旧 {@code version} 触发 CAS 失败会让 {@code context} 写入静默丢失；取最新行既保留同行互斥又避免该丢失。</p>
     *
     * <p><b>⚠️ 调用方须持有事务</b>：按 {@code REQUIRED} 传播加入调用方事务，与 {@code SubTaskService#updateById} 原边界一致。</p>
     *
     * @param subTaskId 子任务 ID
     * @param context   目标 {@code context} 全量内容（整体覆写，非增量合并）；可为 {@code null}
     */
    void updateContext(Long subTaskId, Map<String, Object> context);
}
