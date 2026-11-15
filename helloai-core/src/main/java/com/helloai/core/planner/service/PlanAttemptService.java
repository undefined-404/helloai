package com.helloai.core.planner.service;

import com.helloai.common.constant.PlanAttemptAction;
import com.helloai.core.planner.entity.PlanAttempt;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 拆解操作的 Outbox 意图与栅栏（`TD-16b`）。
 *
 * <p><b>调用时序</b>（首拆为例）：</p>
 * <pre>
 * T1（短事务，毫秒级）
 *   task.status = PLANNING + attemptId + leaseUntil   （现有 beginDecompose）
 *   attemptService.begin(taskId, batchKey, DECOMPOSE, leaseUntil)   ← 本接口
 *   COMMIT   ── 要么「状态 + 意图」都在，要么都不在
 * 事务外（立即）
 *   attemptService.claim(...)    抢占 RUNNING
 *   ... 调模型、解析、落库 ...
 *   attemptService.succeed(...)  收尾；返回 false 说明本次尝试已过期（防悬挂）
 * </pre>
 *
 * <p><b>所有收尾方法返回 boolean</b>：{@code false} = CAS 落空 = 本次尝试已不是有效尝试
 * （被巡检判悬挂重投过，或已被别的节点接管）。调用方必须据此放弃写业务结果——
 * 这是「防悬挂」从设计落到代码的唯一位置。</p>
 */
public interface PlanAttemptService {

    /**
     * **T1：把「我要拆」的意图与任务状态变更写进同一个短事务**（`TD-16b` 的核心）。
     *
     * <p>事务内含两步：{@code taskService.beginDecompose}（CAS {@code PENDING → PLANNING}
     * + 租约 + attemptId）与 {@code begin(...)}（登记本行尝试）。要么都在，要么都不在——
     * 这样不存在「状态改了没人干活」，也不存在「有人干活但状态没改」。</p>
     *
     * <p><b>为什么事务边界放在这一层而不是调用方</b>：调用方 {@code decompose} 是同步入口，
     * 它后面还要跨线程 {@code @Async}；把整段圈进事务会让异步段在事务提交前读不到
     * {@code PLANNING} 状态，触发 {@code executeDecompose} 的「仅 PLANNING 才执行」守卫而空跑。
     * 把边界收在「只含两次写」的方法上，既保证原子又不动异步时序。</p>
     *
     * @param leaseMinutes 任务级租约时长（与 {@code planning-timeout-minutes} 同源）
     * @return attemptId（= 幂等键 {@code batch_key}）；{@code null} = CAS 落空，
     *         说明任务已被别的请求推进，调用方应提示「正在拆解中」
     */
    String beginDecompose(Long taskId, int leaseMinutes);

    /**
     * 登记一次操作意图（幂等）。
     *
     * <p>同一 {@code batchKey} 已登记过则直接返回已有行，不重复插入——它对应
     * {@code uk_plan_attempt_key(batch_key, attempt_no)} 的唯一约束。</p>
     *
     * @param leaseUntil 本次尝试的租约到期时刻（用方按任务级超时配置计算）
     */
    PlanAttempt begin(Long taskId, String batchKey, PlanAttemptAction action, OffsetDateTime leaseUntil);

    /** 抢占：{@code PENDING → RUNNING}。返回 false = 已被别人抢走。 */
    boolean claim(Long id, String ownerNode, OffsetDateTime leaseUntil);

    /** 续租。返回 false = 本次尝试已失效（不该继续跑）。 */
    boolean renew(Long id, Integer attemptNo, OffsetDateTime leaseUntil);

    /**
     * 记录本批应落草案条数（`TD-16e-2`）：完整性判据的事实源。
     *
     * <p>与 {@link #succeed} 分开的原因是**时机**：本方法必须在草案落库**之前**调用，
     * 落库成功后才谈得上收尾。合成一处就只能在落库之后写，而那时已经丢失了「崩溃窗口内
     * 期望条数仍然可读」这个唯一价值（见 {@code PlanAttemptMapper#recordExpectedCount}）。</p>
     *
     * @return false = 本次尝试已被重投 / 接管（{@code attempt_no} 失效），调用方记 WARN 并继续
     */
    boolean recordExpectedCount(Long id, Integer attemptNo, int expectedCount);

    /**
     * 成功收尾。返回 false = **本次尝试已过期**，调用方不得写业务结果。
     *
     * @param expectedCount 本批应落草案条数（完整性校验用；null 表示不更新）
     */
    boolean succeed(Long id, Integer attemptNo, Integer expectedCount, String note);

    /** 失败且可重试（{@code attempt_no + 1}，租约到期后重新抢占）。 */
    boolean failRetryable(Long id, Integer attemptNo, OffsetDateTime nextLeaseUntil, String note);

    /** 失败且不可重试（终态，不再自动重试）。 */
    boolean failTerminal(Long id, Integer attemptNo, String note);

    /** 按幂等键取当前有效的尝试（{@code attempt_no} 最大的一行）。 */
    Optional<PlanAttempt> findLatestByBatchKey(String batchKey);

    /**
     * 扫描并回收悬挂：{@code RUNNING} 且租约过期 ⇒ 转 {@code FAILED_RETRYABLE} 并 {@code attempt_no + 1}。
     *
     * @return 实际回收条数（CAS 落空的不计）
     */
    int reclaimHanging(int limit);
}
