package com.helloai.core.agent.event;

import com.helloai.core.task.entity.SubTask;

/**
 * Agent 事件埋点上下文解析（Phase 0 B2，ADR-001 §3 标识规则）。
 *
 * <p>纯静态工具（与 {@code RetryPolicy} 同款极简风格）：只做 run_id / turn 计算，
 * 不依赖任何 Spring 组件，避免把事件坐标计算传染给各埋点业务类。</p>
 */
public final class AgentEventContextResolver {

    private AgentEventContextResolver() {
    }

    /**
     * Run 标识（ADR-001 §3.1）：{@code run-{taskId}-{roundNum}}。
     *
     * <p><b>Phase 0 现实校准</b>：轮次固定取 1。`task_iteration.round_num` 是报告生成后的
     * 回填快照（{@code TaskIterationServiceImpl.backfillForTask} 用子任务 reworkCount+1 派生），
     * 运行时无数据、非主任务轮次权威字段；待 Plan 实体（ADR §8 升级路径）引入后再升级为
     * {@code run-{planInstanceId}}。</p>
     *
     * @param taskId 主任务 ID（不可空，来自 sub_task.task_id）
     */
    public static String resolveRunId(Long taskId) {
        return "run-" + taskId + "-1";
    }

    /**
     * Turn 序号（ADR-001 §3.2）：{@code 1 + reworkCount + attemptTotal}。
     *
     * <p>rework（{@code reworkCount} +1）、超时回收重派 / N11 回退（{@code attemptTotal} +1）
     * 均产生新 Turn；起始值为 1。{@code reworkFresh} 清零 reworkCount、死信兜底
     * （{@code redispatchDeadLetter}）清零 attemptTotal 与 reworkCount 导致的序号回落为已知近似——
     * 事件仅 write-only 不参与业务决策，回落不影响 B3 对账（对账只校验终态事件 vs 业务表状态）。</p>
     *
     * @param subTask 子任务实体（可空，空时返回 1）
     */
    public static int resolveTurn(SubTask subTask) {
        if (subTask == null) {
            return 1;
        }
        int rework = subTask.getReworkCount() != null ? subTask.getReworkCount() : 0;
        int attempts = subTask.getAttemptTotal() != null ? subTask.getAttemptTotal() : 0;
        return 1 + rework + attempts;
    }
}