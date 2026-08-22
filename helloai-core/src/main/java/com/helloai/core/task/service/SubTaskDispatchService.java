package com.helloai.core.task.service;

import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.port.TaskDispatchPort;

import java.util.regex.Pattern;

/**
 * 子任务调度分配服务。
 *
 * <p>负责把"需要重新进入分配链"的场景统一收口到
 * {@link TaskDispatchPort}，避免 Controller、补偿任务直接改库后绕开
 * ASSIGNED 事件、收件箱通知与自动执行链。</p>
 */
public interface SubTaskDispatchService {

    /**
     * 对 BLOCKED 子任务执行重新调度。
     */
    void dispatchBlockedSubTask(Long subTaskId, Long preferredAgentId);

    /**
     * 对离线 Agent 遗留子任务执行重新调度。
     *
     * <p>这里故意把离线 Agent 作为首选目标交给 {@link TaskDispatchPort}，
     * 由其 fast-fail + fallback 选择替代 Agent，保持角色与熔断逻辑一致。</p>
     *
     * <p>依赖守卫：与 {@link #dispatchPendingSubTaskAuto} 对齐——离线重分配
     * 曾绕过依赖检查，导致"依赖未 DONE 的子任务被直接重派执行"（实测：trae 离线
     * 时依赖 REVIEW 的子任务被直接分给无本机能力的 inner 执行）。未就绪时保持
     * PENDING，等依赖 DONE 后由正常自动分发链接管。</p>
     */
    void redispatchOfflineSubTask(Long subTaskId, Long offlineAgentId);

    /**
     * 初始分配：对 PENDING 子任务执行自动选人并进入弹性调度链。
     *
     * <p>该入口用于"初始分配也按外部优先选人"的目标态演进：
     * 先按角色/策略挑选首选 Agent，再交给 {@link TaskDispatchPort#assignNext(Long, Long)}
     * 执行 fast-fail + 熔断 + fallback 的最终分配。</p>
     *
     * @param subTaskId 子任务 ID
     * @param role      期望角色（通常为 EXECUTOR）
     * @return 实际采用的首选 Agent ID（注意：若首选 fast-fail，最终可能由 fallback 选择其他 Agent）
     */
    Long dispatchPendingSubTaskAuto(Long subTaskId, AgentRole role);

    /**
     * 死信人工兜底：将 DEAD_LETTER 子任务直接指派给指定 Agent。
     *
     * <p>重分配熔断触发后子任务进入 DEAD_LETTER 死信池，自动调度链不再接触。
     * 本方法是唯一的死信恢复入口，由人工确认目标 Agent 后调用：
     * <ol>
     *   <li>校验子任务当前状态必须为 DEAD_LETTER；</li>
     *   <li>清零 reassign_attempt_count（重新投入调度链后计数从头开始）；</li>
     *   <li>直接 changeStatus → ASSIGNED（与现有手动指派语义一致，
     *       changeStatus 内部自带 outbox 事件 + 收件箱通知 + 自动执行链）；</li>
     *   <li>写 task_timeline 审计事件 sub_task_dead_letter_manual_assign。</li>
     * </ol>
     * </p>
     *
     * <p>人工兜底路径不做在线/心跳拦截：人工判断优先，与现有
     * POST /sub-tasks 带 assignedAgent 的手动指派口径保持一致。</p>
     *
     * @param subTaskId 死信子任务 ID
     * @param agentId   人工指定的目标 Agent ID
     * @throws BizException 子任务不存在或状态不是 DEAD_LETTER 时抛出
     */
    void redispatchDeadLetter(Long subTaskId, Long agentId);

    /**
     * N11 外部 Agent 阈值回退入口。
     *
     * <p>由 {@code ExternalAgentFallbackTask} 在 CLI_CLIENT Agent
     * 连续失败达到阈值后调用：
     * <ol>
     *   <li>把子任务重置为 PENDING（清空原 assignedAgent）</li>
     *   <li>在同角色 EXECUTOR 中按 score 降序选一个 API_KEY_LLM 类型的活跃 Agent；</li>
     *   <li>把"原失败 Agent"和"新选中的 LLM Agent"都写入 task_timeline 审计；</li>
     *   <li>交给 {@link TaskDispatchPort#assignNext} 做 fast-fail + 熔断 + fallback
     *       收口，避免 Controller / 补偿任务绕开主调度链。</li>
     * </ol>
     * </p>
     *
     * <p>为什么不直接复用 {@link #dispatchPendingSubTaskAuto}？因为
     * auto 走 {@code AgentSelector.pickPreferred}，仍然可能被
     * {@code preferExternal=true} 选回 CLI_CLIENT，违反"N11 强制回退到 LLM"的语义。
     * 本方法绕过 Selector，直接查询同角色 API_KEY_LLM Agent。</p>
     *
     * @param subTaskId       待重新分发的子任务 ID
     * @param failedAgentId   触发回退的 CLI_CLIENT Agent ID（仅用于审计，不参与实际选人）
     * @param reason          触发回退的原因（用于 task_timeline.payload）
     * @return 实际采用的新 Agent ID（回退后用哪个 API_KEY_LLM Agent 接替）
     * @throws BizException 找不到 API_KEY_LLM 候选时抛出
     */
    Long redispatchForFallback(Long subTaskId, Long failedAgentId, String reason);

    /**
     * ASSIGNED 超时回收：长时间无人 claim 的 ASSIGNED 子任务自动回收到 PENDING 并重新调度。
     *
     * <p>P0 可靠性缺口：任务 ASSIGNED 给 Agent 后，如果该 Agent 长时间不 claim
     *（断连、静默丢弃、Bug），任务就永远卡在 ASSIGNED。该方法将超时任务回收
     * 到 PENDING 并重新进入完整调度链（选人 → 分配 → 通知 → 自动执行）。</p>
     *
     * <p>与其它重分配入口的区别：
     * <ul>
     *   <li>{@link #dispatchBlockedSubTask} —— 对 BLOCKED 子任务按 preferredAgentId 重试</li>
     *   <li>{@link #redispatchOfflineSubTask} —— Agent 被判离线后回收其 ASSIGNED/IN_PROGRESS 子任务</li>
     *   <li>本方法 —— ASSIGNED 后长时间无人 claim，原 Agent 可能仍在线但静默丢弃</li>
     * </ul>
     * </p>
     *
     * @param subTaskId       超时的子任务 ID
     * @param originalAgentId 原分配的 Agent ID（用于审计）
     * @param role            期望角色（用于重新选人，null 时回退 EXECUTOR）
     */
    void redispatchAssignedTimeout(Long subTaskId, Long originalAgentId, AgentRole role);

    // ══════════════════════════════════════════════════════════════
    //  §6.52 执行能力判定（public static：供 ResilientDispatcher 与
    //  SubTaskReviewService 复用，避免各入口各自实现导致判定不一致）
    // ══════════════════════════════════════════════════════════════

    /**
     * §6.52 执行密集信号词表：内容/验收/交付物含本机操作关键词（脚本、服务启动、容器等）。
     */
    Pattern EXECUTION_DENSE_PATTERN = Pattern.compile(
            "(?i)(\\.ps1|\\.sh|\\.bat|\\.py|\\.jar)\\b|docker|kubectl|npm run|mvn |gradle "
                    + "|启动服务|启动应用|执行脚本|运行脚本|部署");

    /** §6.52 执行密集任务判定：内容/验收/交付物含本机操作信号时视为需要本机能力。 */
    static boolean isExecutionDense(SubTask subTask) {
        String text = String.join("\n",
                nvl(subTask.getContent()), nvl(subTask.getAcceptance()), nvl(subTask.getDeliverable()));
        return EXECUTION_DENSE_PATTERN.matcher(text).find();
    }

    /** §6.52 本机执行能力判定：CLI_CLIENT/WEB_BROWSER 天然可本机操作；API_KEY_LLM 需 capabilities.supportsMCP=true。 */
    static boolean hasLocalExecutionCapability(Agent agent) {
        if (agent == null || agent.getAccessType() != com.helloai.common.constant.AgentAccessType.API_KEY_LLM) {
            return true;
        }
        Object supportsMcp = agent.getCapabilities() != null
                ? agent.getCapabilities().get("supportsMCP") : null;
        return Boolean.TRUE.equals(supportsMcp);
    }

    /** §6.52 是否已有人工介入标记（防定时兜底反复触发回退）。 */
    static boolean isManualInterventionMarked(SubTask subTask) {
        return subTask.getContext() != null && subTask.getContext().containsKey("manualIntervention");
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }
}
