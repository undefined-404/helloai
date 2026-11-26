package com.helloai.core.planner.service;

import com.helloai.core.task.port.SubTaskView;

import java.util.List;

/**
 * Planner 任务拆解异步执行服务。
 *
 * <p>拆解异步化改造：{@code decompose} 提交即返回后，LLM 拆解段由本服务在专用线程池
 * {@code plannerDecomposeExecutor} 上异步执行（实现类 {@code PlannerDecomposeAsyncServiceImpl}
 * 以 {@code @Async} 标注）。入口与出口均收敛在本接口，供同步守卫跨类调用以激活异步代理。</p>
 *
 * <p>失败路径由实现内部闭环（回退 PLANNING → PENDING + 记录 {@code task_plan_failed}），
 * 另由 helloai-job 的 {@code PlanningTimeoutTask} 兜底回收卡死在 PLANNING 的任务。</p>
 *
 * <p>`TD-9`：单条草案的 regenerate 走<b>同步</b>执行（用户等待结果，不走
 * {@code plannerDecomposeExecutor}），复用本类全部私有能力（提示词渲染 / 技能过滤 /
 * Kahn 校验 / 依赖回写）。</p>
 */
public interface PlannerDecomposeAsyncService {

    /**
     * 异步执行拆解：选 Planner → 渲染 Prompt → 调 LLM → 解析校验 → 批量落库草案
     * → 依赖序号回写真实 id → 记录 {@code task_plan_generated}。
     *
     * <p><b>身份必须由提交点给出</b>（`G-053`）：{@code attemptId} 与 {@code attemptNo}
     * 是「本次执行属于哪一代」的唯一凭据。异步段<b>不得</b>再读
     * {@code currentDecomposeAttempt} / {@code findLatestByBatchKey} 的当前值代替它们——
     * 派发器重触发时 {@code task.decompose_attempt_id} 不变、只把 {@code attempt_no} 加一，
     * 现读会让旧代读到新代次，两代都以合法身份跑完。</p>
     *
     * <p>幂等防御：任务不在 PLANNING、task 上登记的 attemptId 与传入不符、库中当前代次
     * 与传入不符、或抢占失败，一律直接放弃（fail-close）。任何失败都会回退 PENDING 并记录 timeline。</p>
     *
     * @param taskId    任务 ID（用前已由同步守卫推进至 PLANNING）
     * @param attemptId {@code task.decompose_attempt_id}（= 幂等键 {@code batch_key}）
     * @param attemptNo 本代次，提交点从 {@code PlanAttemptService.DecomposeTicket} 取
     */
    void executeDecompose(Long taskId, String attemptId, int attemptNo);


    /**
     * `AD-5a-3b-2b` 暂存区版重生成：草案只存在于 {@code task_plan_draft} 时按 {@code code} 重写四要素。
     *
     * <p>不变量与 {@link #regenerateById} 完全一致（title / code / dependsOn 不动），
     * 差异只在落点：四要素直接写暂存区，写前跑同一条单草案门禁。</p>
     *
     * @param taskId 所属任务 ID
     * @param code   草案位置身份
     * @return 重生成后的草案快照（id 为暂存区 id）
     */
    SubTaskView regenerateStaged(Long taskId, String code);
}
