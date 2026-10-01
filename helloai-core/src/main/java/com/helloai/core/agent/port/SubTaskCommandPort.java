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
}
