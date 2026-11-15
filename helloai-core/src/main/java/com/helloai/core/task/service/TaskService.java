package com.helloai.core.task.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.common.constant.FinalReportStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.task.entity.Task;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.port.TaskDraft;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 任务核心服务。负责任务级联删除、关联统计、重新发布。
 * 为避免循环依赖，本 Service 直接注入 Mapper 而非依赖其他 Service
 * （AgentInboxService 为无回向依赖的叶子服务，注入以复用门铃链路）。
 */
public interface TaskService extends IService<Task> {

    /**
     * 创建任务（初始状态 PENDING）。
     *
     * <p>按 §6.3 分层红线：实体装配与落库归 Service，Controller 只接收参数与封装返回。
     * 等价于 {@code createTask(title, description, null, null, null)}。</p>
     *
     * @param title       任务标题
     * @param description 任务描述
     * @return 已创建的任务
     */
    Task createTask(String title, String description);

    /**
     * 创建任务（初始状态 PENDING），可指定任务级 SLA 分钟数。
     *
     * <p>{@code slaMinutes} 可空：null=无时限；非 null 时在计划确认（confirmPlan）
     * 阶段按 {@code 确认时刻 + slaMinutes} 下发各子任务 {@code deadline}，
     * 外部 Agent 经 pullTasks 的 {@code deadline} 字段感知时限。
     * 等价于 {@code createTask(title, description, slaMinutes, null, null)}。</p>
     *
     * @param title       任务标题
     * @param description 任务描述
     * @param slaMinutes  任务 SLA 分钟数，null=无时限
     * @return 已创建的任务
     */
    Task createTask(String title, String description, Integer slaMinutes);

    /**
     * 创建任务（初始状态 PENDING），可指定任务级 SLA 与执行策略（收尾）。
     *
     * <p>{@code agentPolicy} / {@code requiredSkills} 可空：null=不设置，落库走 DB 默认值
     * {@code {}} / {@code []}，与旧数据行为完全一致。policy 键结构见 {@code TaskAgentPolicy}。</p>
     *
     * @param title          任务标题
     * @param description    任务描述
     * @param slaMinutes     任务 SLA 分钟数，null=无时限
     * @param agentPolicy    任务级 Agent 指定策略，null=不设置
     * @param requiredSkills 任务要求的能力列表，null=不设置
     * @return 已创建的任务
     */
    Task createTask(String title, String description, Integer slaMinutes,
                    Map<String, Object> agentPolicy, List<String> requiredSkills);

    /**
     * 创建任务（初始状态 PENDING），可指定交付物原型（`TD-1`）。
     *
     * <p>{@code taskArchetype} 可空：null / 空白=不设置，落库走 DB 默认值 {@code BUILD}；
     * 非法值回落 {@code BUILD}。**创建路径必须走本重载**——
     * 走上面的五参重载会把请求里的 {@code taskArchetype} 静默丢弃。</p>
     *
     * @param title          任务标题
     * @param description    任务描述
     * @param slaMinutes     任务 SLA 分钟数，null=无时限
     * @param agentPolicy    任务级 Agent 指定策略，null=不设置
     * @param requiredSkills 任务要求的能力列表，null=不设置
     * @param taskArchetype  交付物原型，null / 空白=不设置
     * @return 已创建的任务
     */
    Task createTask(String title, String description, Integer slaMinutes,
                    Map<String, Object> agentPolicy, List<String> requiredSkills, String taskArchetype);

    /**
     * 任务条件查询（状态过滤 + 创建时间倒序）。
     *
     * <p>按 §6.3 分层红线，条件构造归 Service 层。
     * {@code page == null || page <= 0} 时返回全量列表（包装成 IPage，便于 Controller 统一处理）。</p>
     *
     * @param status   任务状态，可为 null（不过滤）
     * @param page     页码，null 或 <=0 表示不分页
     * @param pageSize 每页条数（仅分页时生效）
     * @return 分页结果或全量列表包装
     */
    IPage<Task> pageTasks(TaskStatus status, Integer page, int pageSize);

    /**
     * 当前用户「进行中」任务（PLANNING + IN_PROGRESS），按更新时间倒序。
     *
     * <p>个人工作台「最近任务」用（PA-4 改版）：只展示仍在推进的任务，
     * 口径与 {@link #pageTasks} 的 owner 宽松并入一致。</p>
     *
     * @param limit 条数上限（≤0 时按实现默认值）
     */
    List<Task> listActiveTasksForOwner(int limit);

    /**
     * `PA-15`：按显式 owner 读取最近推进中的任务（助理工作概览用，不依赖 CurrentUser）。
     *
     * <p>口径与 {@link #listActiveTasksForOwner(int)} 一致：PLANNING / IN_PROGRESS，
     * owner 为空的历史任务宽松并入；按更新时间倒序截断。</p>
     */
    List<Task> listActiveTasksByOwner(Long ownerId, int limit);

    /**
     * 更新任务状态；任务不存在时返回 null（由 Controller 转 R.fail）。
     *
     * @param id     任务 ID
     * @param status 新状态
     * @return 更新后的任务，或 null
     */
    Task updateStatus(Long id, TaskStatus status);

    /**
     * 更新任务标题与描述；任务不存在时返回 null（由 Controller 转 R.fail）。
     * 等价于 {@code updateTask(id, title, description, null, null, null, null)}。
     *
     * @param id          任务 ID
     * @param title       新标题
     * @param description 新描述
     * @return 更新后的任务，或 null
     */
    Task updateTask(Long id, String title, String description);

    /**
     * 更新任务优先级（N-006，C4-S1）：HIGH / MEDIUM / LOW，非法值回落 MEDIUM。
     * 任务不存在时返回 null。
     */
    Task updatePriority(Long id, String priority);

    /**
     * 更新任务基本信息、SLA 与执行策略（收尾）；任务不存在时返回 null。
     *
     * <p>更新语义：null 字段不 set（保持现状，不进入 UPDATE 语句）；
     * {@code agentPolicy} 传空 Map / {@code requiredSkills} 传空列表表示显式清空
     * （写回 {@code {}} / {@code []}）。policy 键结构见 {@code TaskAgentPolicy}。</p>
     *
     * @param id             任务 ID
     * @param title          新标题，null 不更新
     * @param description    新描述，null 不更新
     * @param slaMinutes     新 SLA 分钟数，null 不更新
     * @param agentPolicy    新执行策略，null 不更新
     * @param requiredSkills 新能力列表，null 不更新
     * @param taskArchetype  新交付物原型（`TD-1`）：BUILD / DOCUMENT / RESEARCH / DATA / OPERATE /
     *                       DECIDE；null 或空白不更新，非法值回落 BUILD（人工为权威输入，
     *                       对齐 {@code updateDraft} 的既有口径）
     * @return 更新后的任务，或 null
     */
    Task updateTask(Long id, String title, String description, Integer slaMinutes,
                    Map<String, Object> agentPolicy, List<String> requiredSkills, String taskArchetype);

    /**
     * 关联统计（删除前风险提示）。
     *
     * @param taskId 任务 ID
     * @return 各关联表计数 Map
     */
    Map<String, Object> getRelatedCounts(Long taskId);

    /**
     * 任务级联物理删除。
     *
     * <p>单事务内按外键依赖逆序清理，删除后数据库中不再存在该任务的任何行，
     * 与"消息只是门铃、DB 是唯一事实源"的防重原则天然兼容。</p>
     *
     * @param taskId       任务 ID
     * @param confirmTitle 确认标题（必须与任务标题一致）
     * @return 删除前的影响面统计
     */
    Map<String, Object> deleteTaskCascade(Long taskId, String confirmTitle);

    /**
     * 重新发布任务：状态重置为 PENDING 并重新通知全部 PLANNER。
     *
     * <p>不触碰已有子任务——子任务有独立生命周期与归属校验，重复规划与否
     * 由 PLANNER 侧根据现状决策；使用新 eventId 投递收件箱，
     * (event_id, agent_id) 唯一约束不会与历史通知冲突。</p>
     *
     * @param taskId 任务 ID
     * @return 重新发布后的任务
     */
    Task republish(Long taskId);

    // ══════════════════════════════════════════════════════════════
    //  最终整合报告状态机写口（§12.5：状态迁移唯一收口点）
    // ══════════════════════════════════════════════════════════════

    /**
     * 最终报告状态迁移（CAS）：仅当库中当前状态等于 {@code from} 时置为 {@code to}。
     *
     * <p>这是 {@link com.helloai.common.constant.FinalReportStatus} <b>纯状态迁移</b>的唯一写口：
     * 迁移合法性由 {@link com.helloai.core.task.statemachine.FinalReportStateMachine} 校验，
     * 并发安全由 {@code WHERE final_report_status = from} 的 CAS 条件保证。
     * 调用方不得再自行 {@code lambdaUpdate().set(状态)} 直写本字段。</p>
     *
     * <p>携带其它列（正文/Agent/时间）的复合写回不属于本方法职责，由
     * {@code FinalReportPersistService} 在同一事务内完成，并在写回前调用
     * {@code FinalReportStateMachine.assertTransit} 校验。</p>
     *
     * @param taskId 任务 ID
     * @param from   期望的当前状态（CAS 条件）
     * @param to     目标状态
     * @return true=迁移成功；false=当前状态不是 {@code from}（已被其它链路接管）
     * @throws IllegalStateException 迁移非法（编程错误，快速失败）
     */
    boolean transitFinalReportStatus(Long taskId, FinalReportStatus from, FinalReportStatus to);

    /**
     * 审查链收敛：把 {@code REVIEWING} 的条件写回 {@code DONE}（§12.2 收敛出口收口）。
     *
     * <p>CAS 双条件：{@code final_report_status = REVIEWING} 且
     * {@code final_report_time = reportTime}。后者是<b>陈旧守卫</b>——审查耗时期间报告可能已被
     * 新生成/回滚接管，此时旧链不得覆盖新链状态（返回 false，用方静默放弃）。
     * 前者保证已被兄弟链路（L1/L2/L3 任一路）收敛过的行不会被重复写。</p>
     *
     * @param taskId     任务 ID
     * @param reportTime 事件锚点（= 写回时的 final_report_time）
     * @return true=已收敛；false=版本已变或已被收敛（静默放弃，不是错误）
     */
    boolean convergeFinalReportToDone(Long taskId, OffsetDateTime reportTime);

    /**
     * 列出「审查链丢失」的最终报告孤儿：状态停留 {@code REVIEWING} 且报告写回时间
     * 早于 {@code now - thresholdSeconds}。
     *
     * <p>供 {@code FinalReportReviewOrphanTask} 兜底巡检使用（§12.2 审查链三级容错 L3）。
     * 只返回轻量列（不选 final_report 大字段），避免注解式查询绕开 typeHandler 映射。</p>
     *
     * @param thresholdSeconds 超时阈值（秒）
     * @param limit            单轮批量上限
     * @return 超时未收敛的报告任务（按 final_report_time 升序）
     */
    List<Task> listFinalReportReviewOrphans(int thresholdSeconds, int limit);

    /**
     * 扫描拆解租约已过期的任务（status=PLANNING 且 {@code decompose_lease_until < now}，
     * 按租约到期升序，limit 上限）。
     *
     * <p><b>判活只看租约，不用心跳（`V146`）</b>：拆解线程调模型期间十几分钟不写库，
     * 用 {@code update_time} 判活会误杀正在跑的拆解；「草案在等确认」是独立状态
     * {@code AWAITING_PLAN_CONFIRM}，不在本方法范围内。</p>
     *
     * @param now   当前时刻（租约 &lt; now 视为过期）
     * @param limit 单次最多返回条数
     * @return 租约过期任务列表（可能为空，绝不返回 null）
     */
    List<Task> listTimedOutPlanning(OffsetDateTime now, int limit);

    /**
     * 回收一个租约过期的拆解任务，并**按库中实际情况**决定落到哪个状态。
     *
     * <p>「PLANNING 且有草案」正常不该出现（草案与状态推进同事务）；仍做分流是为让数据异常自愈：
     * 回退 PENDING 会制造死结（re-decompose 报「已存在 N 个子任务」、confirm 报「不是待确认状态」），
     * 推进到待确认则用户至少能看到草案继续。</p>
     *
     * <p><b>这不是判活判据</b>：判活只看租约（{@link #listTimedOutPlanning}）；草案存在性只决定
     * 「回收成什么状态」，不决定「要不要回收」。</p>
     *
     * @return 实际推进到的状态；{@code null} 表示 CAS 未命中（状态已变化）
     */
    TaskStatus recoverTimedOutDecompose(Long taskId);

    // ── 只读快照（RM5 批 4）：供 review 域消费，避免其 import task.entity ──

    /** 按主键查询顶层任务只读快照（语义与 {@link #getById} 一致：不存在返回 null）。 */
    TaskView getView(Long taskId);

    /**
     * 最近创建的任务只读快照（`TD-6` 拆解期上下文快照的「历史同类任务」数据源）。
     *
     * <p><b>按「最近」而非「相似」</b>：无任务相似度索引（无向量列/关键词倒排），只提供「最近 N 个任务」
     * 客观口径，渲染侧据此给「骨架参考」，不宣称是同类任务。</p>
     *
     * @param limit 取几条（≤0 时按实现默认值），仅返回快照字段，不含子任务
     */
    List<TaskView> listRecentViews(int limit);

    // ── 写命令（RM5 批 5a）：供 planner 域消费 ──

    // 状态推进统一走 casStatusForAttempt（带 attemptId 归属校验）。不提供只比状态的重载——
    // 只比状态挡不住跨 attempt 迟到写入，等于留一条会走错的近路。

    /**
     * 进入拆解：一次原子写完成「{@code PENDING → PLANNING} 的 CAS + 拆解租约 + attemptId」。
     *
     * <p><b>为什么合成一个方法</b>：三者必须同时生效。分步写会留下「已 PLANNING 但租约/attemptId 为空」
     * 的窗口——租约为空被巡检漏检（永久卡死），attemptId 为空使幂等键失效（异步段各自生成新 UUID，
     * 唯一索引永不冲突）。</p>
     *
     * <p><b>attemptId 在入口生成而非异步段</b>：幂等要求「同一件事同一个名字」。异步段每次生成新 UUID
     * 等于每次都是新事，唯一索引形同虚设；放入口后「响应丢失重投」与「补偿重跑」拿到同一 attemptId。</p>
     *
     * @param leaseMinutes 本次拆解允许跑多久（租约 = now + 该值）
     * @return 本次拆解的 attemptId；{@code null} 表示 CAS 未命中（状态已被他人改变）
     */
    String beginDecompose(Long taskId, int leaseMinutes);

    /**
     * 读取当前 PLANNING 任务的拆解 attemptId（异步段据此落库与去重）。
     *
     * @return attemptId；任务不存在或非 PLANNING 时返回 {@code null}
     */
    String currentDecomposeAttempt(Long taskId);

    /**
     * 带<b>拆解归属校验</b>的状态 CAS：{@code WHERE id=? AND status=? AND decompose_attempt_id=?}。
     *
     * <p><b>为什么必须连 attemptId 一起比</b>：只比状态挡不住「跨 attempt 的迟到写入」——
     * A 的拆解超租约被巡检推回 PENDING，用户重新拆解拿到 attempt=B、状态回到 PLANNING；
     * 此刻 A 的线程苏醒落库，只比状态的 CAS 会命中 <b>B 的</b> PLANNING，A、B 两批草案同时入库。
     * 唯一索引只约束「同 task + 同 attempt + 同序号」，跨 attempt 挡不住，必须在状态推进这步把 attempt 比上：
     * 校验失败 ⇒ 调用方抛异常回滚 ⇒ A 那批草案一条不剩。</p>
     *
     * @param attemptId 调用方自己的拆解标识；{@code null} 时直接返回 {@code false}（无归属可比即不放行）
     * @return 是否命中一行（命中 = 本次拆解仍是该任务当前那次拆解）
     */
    boolean casStatusForAttempt(Long taskId, TaskStatus expect, TaskStatus target, String attemptId);

    /** 由创建物料建任务（写命令）并返回落库后的只读快照（RM5 批 5a）。 */
    TaskView createFromDraft(TaskDraft draft);

    /**
     * 按键合并任务 {@code context}（`TD-13 S4`：阻断类门禁 findings 的持久落点）。
     *
     * <p><b>只覆盖传入的 key</b>，未传入的 key 保留库中现值：先取<b>最新行</b>再逐键写入，
     * 避免调用方拿旧快照整段覆盖掉两次写入之间第三方改动的 key（{@code @Version} 只保护
     * 版本号，不保护内容）。null / 空 map 为空操作；任务不存在时静默返回。</p>
     */
    void updateContext(Long taskId, Map<String, Object> context);

    /**
     * 同 {@link #updateContext}，但以 `REQUIRES_NEW` 独立事务提交——**审计写点专用**。
     *
     * <p><b>为什么需要</b>：`TD-9` 单条展开/重生成带 {@code @Transactional}，门禁阻断抛 {@code BizException}
     * 会让同事务的 findings 一并回滚，库里不留排障线索。审计数据不与业务共命运（同 {@code SubmissionActivityRecorder}）。</p>
     *
     * <p><b>为什么不把 {@link #updateContext} 直接改成独立事务</b>：{@code task.context} 同时承载业务语义
     * （需求摘要等），需与业务同生共死的写入必须能随外层回滚；只把独立事务绑在本入口，避免「业务已回滚、
     * context 却说成功」的假记录。</p>
     */
    void updateContextInNewTx(Long taskId, Map<String, Object> context);
}
