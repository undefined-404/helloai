package com.helloai.common.constant;

/**
 * 拆解操作的一次尝试的结论（五态，`TD-16b`）。
 *
 * <p><b>为什么不是「成功 / 失败」两态</b>：现有代码 {@code catch (Exception) → rollbackToPending}
 * 一刀切，把「模型超时（重试可能成功）」和「输出解析不了（重试必然同样失败）」当成同一件事。
 * 前者重试是修复，后者重试是白烧额度。**「要不要重试」必须在落库那一刻就判出来**——
 * 事后没人能凭一条异常消息重判当时是网络抖了还是模型真的不会输出。</p>
 *
 * <p><b>为什么不落「失败」通用值</b>：一个能表示一切的 {@code FAILED} 等于什么都没表示。
 * 消费方拿到它只能再写一次 if-else 去分，「分在哪」就没有唯一事实源了。</p>
 *
 * <p><b>与 {@code trade-cloud} 的差别</b>：那边是三态（成功 / 可重试 / 终止），
 * 这里多出 {@link #PENDING} 与 {@link #RUNNING} 两态，因为我们把「意图落库」和
 * 「执行」拆成了两步（Outbox）——意图在库里就必须有个状态，不能等执行完才第一次写。</p>
 */
public enum PlanAttemptStatus {

    /**
     * 已登记、无人接手。
     *
     * <p>出现在「T1 事务提交后、{@code @Async} 还没抢到线程」的窗口，以及
     * 「{@code @Async} 被线程池拒绝」的降级路径。派发器的兜底扫描会抢占它。</p>
     */
    PENDING,

    /** 已抢占、租约有效。{@code owner_node} 记录谁在跑。 */
    RUNNING,

    /** 终态：草案完整落库 + 状态推进成功。 */
    SUCCEEDED,

    /**
     * 终态：重试结果一样，不要重试。
     *
     * <p>典型：输出 JSON 解析失败 / 必填字段缺失 / 深度超限 / 依赖越界 / 规则门 block。
     * 处置是 {@code task → PENDING} 并提示用户重拆（人改需求后是新 batch_key，不是重试）。</p>
     */
    FAILED_TERMINAL,

    /**
     * 可重试：{@code attempt_no + 1}，租约到期后由派发器重新抢占。
     *
     * <p>典型：模型调用超时 / 网络中断 / 数据库抖动 / 行锁冲突。上限 3 次，
     * 到顶转 {@link #FAILED_TERMINAL}——否则一个稳定坏掉的 provider 会被无限重试。</p>
     */
    FAILED_RETRYABLE;

    /** 是否终态（不会再被派发器捡起来）。 */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED_TERMINAL;
    }

    /** 是否还需要派发器关注（PENDING 待抢占 / RUNNING 待判活）。 */
    public boolean isActive() {
        return this == PENDING || this == RUNNING;
    }

    /** 该结论下是否允许自动重试。 */
    public boolean isRetryable() {
        return this == FAILED_RETRYABLE;
    }

    /** 值域校验：null / 未知值返回 {@code false}（供解析侧做防御式判断）。 */
    public static boolean isKnown(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        for (PlanAttemptStatus v : values()) {
            if (v.name().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
