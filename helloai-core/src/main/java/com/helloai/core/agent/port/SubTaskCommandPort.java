package com.helloai.core.agent.port;

import com.helloai.common.constant.SubTaskStatus;

/**
 * 子任务命令端口（agent 域消费，task 域实现）。
 *
 * <p><b>端口归属判据</b>（{@code doc/HelloAI_CODE_STYLE.md} §7.2）：同 {@link SubTaskQueryPort}——
 * 消费方 agent <b>低于</b>提供方 task ⇒ 端口落消费方 {@code agent.port}、适配器落提供方
 * task 域，实现侧依赖 {@code task → agent} 属<b>顺向合法</b>。</p>
 *
 * <p><b>为什么是「不透明命令」而不是「读端口 + 写端口」</b>（备忘录 §16.8）：{@link #startIfNeeded}
 * 属<b>带判定的状态推进</b> ——「判定 + 写」必须整体留在 task 域，agent 侧只表达意图、不做裁决。
 * 若把判定留在 agent（消费方先读状态、自行裁决、再调写），等于把 task 的状态机搬进 agent 域；
 * 一旦判定日后需要读库（加锁读 → 判定 → 写），跨域两段式调用就会撕开事务边界。
 * 故此处整体不透明化。<b>反例边界</b>：纯无判定的副作用写（如收件箱通知）才适合走事件，
 * 二者不可混用同一手法。</p>
 *
 * <p><b>「判定留消费方」还是「判定搬提供方」的划界（2026-10-01 W7 固化，重要）</b>：
 * 与 {@link #startIfNeeded} 不同，{@link #start} / {@link #claimAtomic} / {@link #block}
 * 是<b>原子命令</b>，判定留在消费方。判据不是「有没有判定」，而是<b>判定产出的东西是什么</b>：</p>
 * <ul>
 *     <li>判定产出的只是**状态变更决策**（该不该推进、推进到哪）⇒ 属提供方领域规则，
 *         搬进提供方适配器（{@code startIfNeeded} 即此类；见 W3 整类删除）；
 *     </li>
 *     <li>判定产出的是**对外响应契约**（MCP 工具的 {@code reason} 码、HTTP 语义、协议分支）
 *         ⇒ 那是**消费方协议适配层**的职责，必须留在消费方；提供方只提供原子命令，
 *         不得把「协议分支」搬进领域（否则领域会被各种调用方的响应格式污染）。</li>
 * </ul>
 * <p>W7 的 MCP 通道即第二种：{@code subtask_not_found / not_task_owner / invalid_status:XXX /
 * dependency_not_ready / race_condition_or_invalid_status} 都是<b>对外协议码</b>，
 * 故 {@code McpToolServiceImpl} 保留前置校验分支，仅把写入动作经本端口表达。</p>
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
     * <p>与 {@link #startIfNeeded} 的区别：本方法**不做状态白名单裁决**，直接委派
     * {@code SubTaskService#start}——合法性由提供方 {@code SubTaskStateMachine} +
     * {@code @Version} 乐观锁保障。调用方（MCP 工具）因需产出**对外 reason 码**
     * 而自带前置校验分支，属消费方协议适配职责（见接口 javadoc 划界说明）。</p>
     *
     * @param subTaskId 子任务 ID
     */
    void start(Long subTaskId);

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
     * @param subTaskId 子任务 ID
     * @param reason    阻塞原因
     * @param agentId   上报 Agent ID
     */
    void block(Long subTaskId, String reason, Long agentId);
}
