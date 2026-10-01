package com.helloai.core.agent.port;

import java.util.List;

/**
 * 子任务「末轮评审上下文」读 + 条件回填端口（agent 域消费，task 域实现）。
 *
 * <p><b>端口归属判据</b>（{@code doc/HelloAI_CODE_STYLE.md} §7.2）：同 {@link SubTaskQueryPort}——
 * 消费方 agent <b>低于</b>提供方 task ⇒ 端口落消费方 {@code agent.port}、适配器落提供方
 * task 域，实现侧依赖 {@code task → agent} 属<b>顺向合法</b>。</p>
 *
 * <p><b>为什么回填是「不透明命令」</b>（备忘录 §16.8）：{@link #backfillExecutorDoneIssues}
 * 是典型的「重读 → 判定 → 写」，三个动作必须在同一处完成 —— 消费方只表达意图
 * （回填哪一轮、写什么），判定（轮次是否已变、是否已被回填）与写入整体留在提供方。
 * 消费方侧的分段锁仅用于<b>串行化并发回填</b>（原实现即为「单实例安全」语义），
 * 不承担正确性判定，故不因反转而改变并发语义。</p>
 *
 * <p>实现见 {@code task.service.impl.SubTaskReviewContextPortAdapter}
 * （context／reviewHistory 的 schema 解析集中在提供方，避免两份口径漂移）。</p>
 */
public interface SubTaskReviewContextPort {

    /**
     * 读取末轮评审上下文。
     *
     * @param subTaskId 子任务 ID
     * @return 末轮评审视图；<b>子任务不存在或无 {@code reviewHistory} 时返回 {@code null}</b>
     *         （与原 agent 侧 {@code peekLastRound} 的 null 语义一致）
     */
    LastReviewContext loadLastReviewContext(Long subTaskId);

    /**
     * 条件回填末轮 {@code executorDoneIssues}（不透明命令）。
     *
     * <p>提供方内部：重读实体 → 解析末轮 → 若轮次已变则拒绝 → 若已回填过则拒绝 →
     * 否则拷贝 history、覆写末轮 {@code executorDoneIssues} 后落库。</p>
     *
     * @param subTaskId  子任务 ID
     * @param targetRound 期望的末轮轮次（调用方观测值）
     * @param doneIssues  待回填的「执行者已解决项」
     * @return 处置结果；调用方据此决定是否记录 timeline（见 {@link BackfillOutcome}）
     */
    BackfillOutcome backfillExecutorDoneIssues(Long subTaskId, int targetRound, List<String> doneIssues);

    /**
     * 条件回填的处置结果。
     *
     * <p>各分支与原 agent 侧实现的观测行为<b>逐条对应</b>（便于迁移时保持 timeline 事件不变）：
     * <ul>
     *   <li>{@code SUB_TASK_MISSING} ← 原「锁内重读为 null → 静默 return」</li>
     *   <li>{@code SKIPPED_ROUND_CHANGED} ← 原「末轮缺失或轮次已变 → 记 skipped/round_changed」</li>
     *   <li>{@code SKIPPED_ALREADY_FILLED} ← 原「已被并发回填 → 静默 return」</li>
     *   <li>{@code WRITTEN} ← 原「进入写入分支（即使内部因 history 为空/轮次不符而空写，
     *       原代码亦继续记 success）」</li>
     * </ul></p>
     */
    enum BackfillOutcome {
        /** 子任务不存在（原：锁内重读为 null，静默返回）。 */
        SUB_TASK_MISSING,
        /** 末轮缺失或轮次与期望不符（原：记 skipped/round_changed）。 */
        SKIPPED_ROUND_CHANGED,
        /** 已被（并发）回填过（原：静默返回）。 */
        SKIPPED_ALREADY_FILLED,
        /** 已进入写入分支。 */
        WRITTEN
    }
}
