package com.helloai.core.task.service;

import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.port.TaskDraft;
import com.helloai.core.task.port.TaskView;

import java.util.List;

/**
 * 派生式子任务的任务域写原语（`ADR-008`）。
 *
 * <p><b>为什么单独一个服务而不是塞进 {@link TaskService}</b>：本服务只依赖同域 Mapper
 * （{@code TaskMapper} / {@code SubTaskMapper}）与状态机契约，因此可以被
 * {@code SubTaskServiceImpl} 注入而不形成 {@code TaskService → SubTaskService → TaskService}
 * 的循环。planner 域也只需依赖 {@code task.service} 契约，不触碰 {@code task.mapper} /
 * {@code task.entity}（架构守卫 `planner -> task.mapper = 0`）。</p>
 *
 * <p><b>草案期与执行期的差别</b>：草案期父 SubTask 还不存在，父锚是
 * {@code (parent_task_id, parent_draft_code)}；父任务确认物化 SubTask 时才回填
 * {@code parent_sub_task_id}（此时清空 {@code parent_draft_code}，`V148` 的
 * {@code chk_task_parent_anchor` 禁止双锚共存）。执行期重构的父锚直接是
 * {@code parent_sub_task_id}（AD-4）。</p>
 */
public interface TaskDerivationService {

    /**
     * 按草案父锚查「活跃派生」：同一条草案最多只有一个未取消的派生 Task
     * （`uk_task_parent_draft_active` 的代码侧读法）。
     *
     * @return 派生 Task 快照；没有则 {@code null}
     */
    TaskView findActivePlanDerivation(Long parentTaskId, String parentDraftCode);

    /**
     * 列出某任务下所有未达终态的草案期派生（整批拒绝时级联取消用）。
     */
    List<TaskView> listActivePlanDerivations(Long parentTaskId);

    /**
     * 草案期派生：建独立 Task `Y`（父锚、`depth+1`、继承 archetype / owner / SLA）。
     *
     * <p>落库即返回，不触发拆解——拆解是 planner 域的动作（`decompose(Y)`），
     * 必须在本事务提交后执行，否则异步段读不到 `Y`。</p>
     *
     * @throws com.helloai.common.base.BizException 400：参数缺失 / 父任务不存在 /
     *         重复派生撞唯一索引 / 派生层级超过全局上限
     */
    TaskView createPlanDerivation(Long parentTaskId, String parentDraftCode, SubTaskView draft);

    /**
     * 确认物化时回填父 SubTask：`Y.parent_sub_task_id = A2.id`，同时清空
     * {@code parent_draft_code}（双锚互斥）。幂等：已绑定同一 id 视为成功。
     */
    void bindParentSubTask(Long derivedTaskId, Long parentSubTaskId);

    /**
     * 执行期重构派生（`ADR-008` §13，AD-4）：会话定稿时建 `Y`，父锚直接是
     * {@code parent_sub_task_id}，并在<b>同一事务</b>把父 SubTask 置 `AWAITING_DERIVED`。
     *
     * <p>与 {@link #createPlanDerivation} 的差别：父 SubTask 已存在，不需要草案 code；
     * 入口状态必须是 `RESTRUCTURING`（人工接管态），`derivation_reason = RESTRUCTURE`，
     * `derivation_round = 该父已有派生行数 + 1`（允许重复重构，§13.3）。</p>
     *
     * @throws com.helloai.common.base.BizException 400：父 SubTask 不存在 / 非重构中 /
     *         层级超限 / 已有活跃派生
     */
    TaskView createRestructureTask(TaskDraft draft, Long parentSubTaskId);

    /**
     * 草案期派生（对话版，`ADR-008` §5）：草案「拆出去」走对话、终稿确认时建 `Y`。
     *
     * <p>与 {@link #createPlanDerivation} 的区别只在<b>内容来源与物化时机</b>：那条路径用
     * 草案原文直接建 Y 并立刻自拆；本方法用**对话确认过的终稿**（{@code TaskDraft}）建 Y，
     * 因而父锚仍是 {@code (parent_task_id, parent_draft_code)}，但要求/交付/验收来自终稿，
     * 其中包含用户在对话里补的执行前提（仓库 / 运行时 / 数据源）。</p>
     *
     * <p><b>草案存在性不在这里校验</b>：草案在 planner 域（{@code task_plan_draft}），
     * 从 task 域反查会形成反向依赖。调用方（`ClarifyFinalizeServiceImpl`）在定稿前已确认。</p>
     *
     * <p>落库即返回，<b>不触发拆解</b>——拆解是 planner 域动作，必须在事务提交后执行。</p>
     *
     * @throws com.helloai.common.base.BizException 400：参数缺失 / 父任务不存在 /
     *         已有活跃派生 / 派生层级超过全局上限
     */
    TaskView createDraftDeriveTask(TaskDraft draft, Long parentTaskId, String parentDraftCode);

    /**
     * 执行期重构派生是否「还有父锚可挂」——<b>只判父 SubTask 行是否还在</b>
     * （按主键查，逻辑删除行按不存在处理）。
     *
     * <p><b>为什么单独一个探测</b>：二次拆分的会话「重新生成任务和子任务」不应被上级任务的
     * 存在性硬卡死（解耦方向一）。父 SubTask 已被级联删除时父锚已断，调用方据此回退普通
     * 任务创建（新任务成为独立根任务），而不是撞 {@link #createRestructureTask} 的
     * 400「父 SubTask 不存在」把用户堵死在重建入口。</p>
     *
     * <p><b>状态 / 父任务 / 派生层级不在本探测内</b>：`AWAITING_DERIVED`、`BLOCKED` 等状态
     * 是可纠正的（转回 `RESTRUCTURING` 即可），必须让用户看到 400 原因；层级超限同理。
     * 这些情况静默降级会绕过层级上限、并让血缘在不通知用户的前提下断开
     * （用户以为派生链还在，实际已是游离任务）。</p>
     *
     * @return {@code true}=父锚还在，可尝试建 Y（不保证必成：探测与创建之间的并发删除
     *         仍由 {@link #createRestructureTask} 的 400 防御）；{@code false}=父锚已断
     */
    boolean restructureViable(Long parentSubTaskId);

    /**
     * 按执行期父锚查「活跃派生」：同一子任务最多只有一个未取消、未删除的派生 Task
     * （`uk_task_parent_sub_task_active` 的代码侧读法：
     * {@code parent_sub_task_id = ? AND deleted = 0 AND status <> 'CANCELLED'}）。
     *
     * <p><b>为什么不只藏在断言里而单列一个只读方法</b>：这是「该子任务还能不能再拆一次」的
     * **唯一事实源**。{@code AWAITING_DERIVED} 只是派生结果的投影，派生任务被物理删除后
     * 投影即失实——界面要提示「已完成拆分（#xxx）」、编排要判重，都必须读这个事实，
     * 而不是读状态。同类先例：{@link #findActivePlanDerivation}（草案期）。</p>
     *
     * @return 活跃派生 Task 快照（只带轻量列）；没有则 {@code null}
     */
    TaskView findActiveRestructureDerivation(Long parentSubTaskId);

    /**
     * 派生前置校验 + 标记自愈：把「能不能再拆一次」从<b>状态判断</b>改为<b>事实判断</b>。
     *
     * <ul>
     *   <li>{@code RESTRUCTURING} —— 放行；</li>
     *   <li>{@code AWAITING_DERIVED} —— 核对事实：存在活跃派生 ⇒ 400 且告知派生任务 id；
     *       不存在（派生任务已删 / 已作废）⇒ 标记失实，自愈回 {@code RESTRUCTURING} 后放行；</li>
     *   <li>其它状态 —— 400「仅重构中状态可派生独立任务」。</li>
     * </ul>
     *
     * <p><b>为什么在入口核对，而不是在删除路径逐个回滚父状态</b>：删除路径有多条
     * （删主任务 / 删派生任务 / 作废派生任务），逐条补回滚是「同一事实两处写」，漏一条就再次
     * 分叉；对账②（{@code TaskMapper#selectTerminalDerivationsAwaitingParent}）已承担异步修正，
     * 但它要求 {@code t.deleted = 0}，<b>物理删除的派生行扫不到</b>，父会永久停在
     * {@code AWAITING_DERIVED}。入口按事实判定天然幂等，且不依赖删除路径是否补全。</p>
     *
     * <p>{@code AWAITING_DERIVED → RESTRUCTURING} 是状态机为「派生失败 / 废除」预留的出边
     * （{@code SubTaskStateMachine:41}），非新增语义。</p>
     *
     * @throws com.helloai.common.base.BizException 400：父 SubTask 不存在 / 非可派生状态 /
     *         已有活跃派生
     */
    void assertDerivable(Long parentSubTaskId);
}
