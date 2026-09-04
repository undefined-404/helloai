package com.helloai.core.task.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.port.TaskDispatchPort;
import lombok.Data;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 子任务领域服务。
 */
public interface SubTaskService extends IService<SubTask> {

    @Transactional(rollbackFor = Exception.class)
    SubTask create(SubTask subTask, Long assignedAgentId);

    /**
     * 子任务条件查询（主任务 / 状态 / 负责 Agent 组合过滤）。
     *
     * <p>按 §6.3 分层红线从 SubTaskController 收口：条件构造归 Service 层。
     * {@code page == null || page <= 0} 时返回全量列表（包装成 IPage，便于 Controller 统一处理），
     * 兼容 SKILL.md 外部 Agent 不分页调用契约；否则按分页参数返回。</p>
     *
     * @param taskId          主任务 ID，可为 null
     * @param status          子任务状态，可为 null
     * @param assignedAgentId 负责 Agent ID，可为 null
     * @param page            页码，null 或 <=0 表示不分页
     * @param pageSize        每页条数（仅分页时生效）
     * @return 分页结果或全量列表；绝不返回 null
     */
    IPage<SubTask> list(Long taskId, SubTaskStatus status, Long assignedAgentId, Integer page, int pageSize);

    /**
     * 待领取子任务列表（PENDING 状态，按创建时间倒序）。
     *
     * <p>按 §6.3 分层红线从 SubTaskController 收口。</p>
     */
    List<SubTask> listAvailable();

    /**
     * 指定 Agent 负责的子任务列表（按创建时间倒序）。
     *
     * <p>按 §6.3 分层红线从 SubTaskController 收口。</p>
     */
    List<SubTask> listMine(Long assignedAgentId);

    /**
     * 按子任务主键加行级锁读取。
     *
     * <p>用于命令创建等需要"读现状 + 紧接写入"原子化的路径，
     * 避免在同一子任务上出现并发重复发命令。</p>
     *
     * <p><b>必须在事务内调用</b>：本方法只发 {@code SELECT ... FOR UPDATE}，
     * 不自行开启事务；行锁随调用方事务存续而释放（方法返回即释放）。
     * 自开事务会立刻释放锁，失去互斥意义。当前唯一主代码调用方
     * {@code ExecutionCommandServiceImpl.createAssignedCommand} 已满足该前提。</p>
     */
    SubTask getByIdForUpdate(Long subTaskId);

    /**
     * ready 语义判定（内循环依赖编排）：{@code depends_on} 中所有前置子任务
     * 均为 DONE 才允许分发；空依赖直接就绪（旧数据行为与现状完全一致）。
     *
     * <p>分发链两处复用：{@code SubTaskDispatchService.dispatchPendingSubTaskAuto}
     * 与 {@code SubTaskPendingOrphanTask} 孤儿扫描，依赖检查逻辑收敛在本方法。</p>
     */
    boolean isReady(SubTask subTask);

    /**
     * 写入依赖 id 数组：手工拼 JSON 数字数组后走专用 Mapper SQL（jsonb），
     * 不走 updateById 全列覆盖，避免乐观锁 version 参数依赖。
     * 专供 PlannerAnalysisService 拆解落库后的"序号→真实 id"回写。
     *
     * <p>不能用全局 ObjectMapper 序列化：它注册了 Long→String（JacksonConfig，
     * 防前端精度丢失），会把依赖 id 写成字符串数组，导致 ready 守卫读取时
     * 归一化失败、有依赖节点被误判为就绪。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    void updateDependsOn(Long subTaskId, List<Long> dependsOnIds);

    @Transactional(rollbackFor = Exception.class)
    void changeStatus(Long subTaskId, SubTaskStatus newStatus, Long agentId);

    @Transactional(rollbackFor = Exception.class)
    void changeStatus(Long subTaskId, SubTaskStatus newStatus, Long agentId, Map<String, Object> contextPatch);

    @Transactional(rollbackFor = Exception.class)
    void claim(Long subTaskId, Long agentId);

    @Transactional(rollbackFor = Exception.class)
    void start(Long subTaskId);

    @Transactional(rollbackFor = Exception.class)
    void submit(Long subTaskId);

    @Transactional(rollbackFor = Exception.class)
    void complete(Long subTaskId);

    @Transactional(rollbackFor = Exception.class)
    void pause(Long subTaskId);

    @Transactional(rollbackFor = Exception.class)
    void resume(Long subTaskId);

    /**
     * 驳回统一补发收件箱通知（自动核验 rejectAndRework 与人工驳回 rework/reworkFresh 共用），
     * 摘要携带最近一轮 review 结果（评分/评语/问题），外部 Agent 轮询 pullTasks 即可感知返工原因。
     * 发送失败只 warn 不阻断（返工主链路优先）。
     *
     * <p>自动驳回返工（Phase 0 A3 共享预算，LOG-20260904-007）：打回 = 新一轮执行尝试，
     * 计入 {@code attempt_total}（与调度重分配同源）；预算耗尽直接转 DEAD_LETTER 不再打回。</p>
     *
     * @return true = 已打回 REWORK；false = 共享预算耗尽，已转 DEAD_LETTER（调用方需跳过
     *         执行命令补发等"将开启新一次执行尝试"的后续动作）
     */
    @Transactional(rollbackFor = Exception.class)
    boolean rework(Long subTaskId, Long reworkAgentId);

    /**
     * §6.57 人工驳回重置：返工计数归零并清除人工介入标记，开启新一轮执行。
     *
     * <p>与 {@link #rework} 的分工：rework 供自动核验驳回使用（reworkCount 累加，
     * 达 {@code auto-review-max-rework} 后停留 REVIEW 等人工）；人工审查（review API）
     * 驳回代表用户拍板开启新一轮，必须重置计数并清除 manualIntervention 标记，
     * 否则新执行者提交后仍命中 skip_max_rework 跳过自动核验、任务无节点流转。</p>
     *
     * @param subTaskId      子任务 ID
     * @param reworkAgentId  改派目标 Agent（可空：驳回原执行者重做，执行者保持不变）
     */
    @Transactional(rollbackFor = Exception.class)
    void reworkFresh(Long subTaskId, Long reworkAgentId);

    /**
     * §6.52 人工介入标记：写入子任务 context.manualIntervention。
     *
     * <p>自动链路（返工达上限、降级能力不匹配）不再继续打回/重派时调用，
     * 标记该子任务等待人工处置；前端据此展示"人工介入"面板，
     * 由用户选择 agent 驳回改派或直接通过。幂等覆盖写入，失败不抛异常。</p>
     *
     * @param subTaskId 子任务 ID
     * @param reason    触发原因（rework_limit / fallback_skip_execution_dense 等）
     * @param extra     附加信息（reworkCount / maxRework / failedAgentId 等，可空）
     */
    @Transactional(rollbackFor = Exception.class)
    void markManualIntervention(Long subTaskId, String reason, Map<String, Object> extra);

    @Transactional(rollbackFor = Exception.class)
    void block(Long subTaskId);

    @Transactional(rollbackFor = Exception.class)
    void block(Long subTaskId, String reason, Long reporterAgentId);

    @Transactional(rollbackFor = Exception.class)
    void cancel(Long subTaskId);

    /**
     * 将 PENDING 子任务分配给指定 Agent（§4.5 熔断调度入口）。
     *
     * <p><b>⚠️ 调用约束：本方法只能由 {@link TaskDispatchPort} 实现方（ResilientDispatcher）调用！</b>
     * 业务方必须走 {@code taskDispatchPort.assignNext(agentId, subTaskId)}，
     * 直接调用本方法将<b>绕过熔断保护</b>，导致不可用 Agent 仍被分配任务。</p>
     *
     * <p>只允许 PENDING 状态，避免抢任务冲突。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    void assignNext(Long agentId, Long subTaskId);

    /**
     * 将子任务重置为待重新调度的 PENDING 状态。
     *
     * <p>该方法用于离线补偿、阻塞重分配等"需要重新走弹性调度器"的系统路径。
     * 会清空当前 assignedAgent，让后续 {@link TaskDispatchPort#assignNext(Long, Long)}
     * 重新发布标准 ASSIGNED 事件与自动执行链。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    SubTask resetToPendingForDispatch(Long subTaskId, Set<SubTaskStatus> allowedStatuses);

    @Transactional(rollbackFor = Exception.class)
    void reassign(Long subTaskId, Long newAgentId);

    /**
     * 批量创建子任务（ 派发控制台——同内容 fan-out 派给多个 Agent）。
     *
     * <p>传入的是已经由 Controller 完成 DTO→Entity 映射的实体集合 + 各自关联的 assignedAgentId。
     * 逐项调用现有 {@link #create(SubTask, Long)} 单建逻辑：</p>
     * <ul>
     *   <li>复用单建的所有装配与状态机逻辑（禁止复制方法体）</li>
     *   <li>每项自身独立事务，单项失败不阻挡其他项（catch 隔离）</li>
     *   <li>返回成功创建的实体列表（不含失败项）</li>
     * </ul>
     *
     * <p>Controller 不感知事务边界，只负责传入、调用、转换为 Response DTO。</p>
     *
     * @param items 创建参数（实体 + assignedAgentId）
     * @return 成功创建的 SubTask 列表（顺序与输入一致，跳过失败项）
     */
    @Transactional(rollbackFor = Exception.class)
    List<SubTask> createBatch(List<BatchCreateItem> items);

    /**
     * 批量创建单项参数。
     *
     * <p>实体已由 Controller 完成 DTO 映射（含 taskId / moduleId / title / content /
     * deliverable / acceptance / priority / status=PENDING）；assignedAgentId 为直派 Agent ID
     * （可空，为空时走 PENDING 等自动派发）。</p>
     */
    @Data
    class BatchCreateItem {
        private SubTask subTask;
        private Long assignedAgentId;
    }

    /**
     * 列出超过阈值秒数仍处于 REVIEW 且未被人工介入的子任务（孤儿 REVIEW）。
     *
     * <p>作为 com.helloai.core.review.SubTaskReviewService#onSubmittedForReview
     * 事件链的兜底扫描：当 AFTER_COMMIT 事务事件因线程池 / 序列化等原因丢失时，
     * 本方法提供基于 DB 状态的二次发现能力。</p>
     *
     * <p>§6.52 修复：不能以"已有审查记录"排除候选——返工达上限的任务同样持有
     * review_record，若事件链丢失，这类任务将永远不被兜底扫描，永久卡死 REVIEW
     * （前端人工介入面板依赖本扫描写入 manualIntervention 标记）。改为排除
     * 已标记人工介入的任务（人工处置中，不再自动打扰）。</p>
     *
     * @param thresholdSeconds 子任务 update_time 早于 now - thresholdSeconds 的才进入候选
     * @param limit            返回上限
     * @return REVIEW 孤儿子任务列表
     */
    List<SubTask> listReviewOrphans(int thresholdSeconds, int limit);

    /**
     * 列出最近有变更的子任务（Phase 0 B3 事件对账候选源）。
     *
     * <p>事件是业务状态的投影（ADR-001 §5.3），对账必须<b>以业务表为候选源</b>：
     * 只扫描最近变更的子任务，校验其是否发出了与当前状态匹配的终态事件；
     * 若以事件流为候选源，埋点失败/缺失的子任务永远不会进入对账视野。</p>
     *
     * @param since 只统计 update_time &gt;= since 的子任务（对账窗口）
     * @param limit 返回上限（窗口内变更量超限时本轮截断，下一轮继续）
     * @return 最近变更子任务列表（按更新时间倒序，绝不返回 null）
     */
    List<SubTask> listRecentlyChanged(OffsetDateTime since, int limit);

    /**
     * 看门狗续期：更新当前节点持有的全部执行租约（Phase 0 A2.3）。
     *
     * <p>由 {@code WatchdogLeaseRenewTask}（helloai-job，每节点独立运行、不加 ShedLock）
     * 周期调用，仅续 {@code owner = 当前节点名} 的 IN_PROGRESS 子任务租约；
     * 单条基于 {@code SubTask.@Version} 乐观锁 CAS，若该行已被 Reconciler 回收
     * （状态离开 IN_PROGRESS / version 变更）则跳过。</p>
     *
     * @param newLeaseUntil 统一的续期目标时间（{@code now + ttl}）
     * @param limit         单轮续期上限
     * @return 实际续期成功条数
     */
    @Transactional(rollbackFor = Exception.class)
    int renewCurrentNodeLeases(OffsetDateTime newLeaseUntil, int limit);

    /**
     * 租约过期回收：把 {@code lease_until < now} 的 IN_PROGRESS 子任务退回 PENDING（Phase 0 A2.4）。
     *
     * <p>由 {@code LeaseReconcilerTask}（helloai-job，ShedLock 集群单例）周期调用，
     * 处理 Worker 崩溃 / 宕机后无人续租的任务：回收为 PENDING + 清空
     * assignedAgentId / owner / leaseUntil，交由既有分发链重新派发；同时写
     * {@code task_timeline} 回收事件供审计。单条基于 @Version 乐观锁 CAS，
     * 与 Watchdog 续期并发竞争时失败方自动跳过（赢者生效）。</p>
     *
     * @param limit 单轮回收上限
     * @return 实际回收条数
     */
    @Transactional(rollbackFor = Exception.class)
    int reclaimExpiredLeases(int limit);

    // ══════════════════════════════════════════════════════════════
    //  阶段五 agent→task.mapper 清零承接（agent 域只依赖本服务接口）
    // ══════════════════════════════════════════════════════════════

    /**
     * 指定 Agent 名下子任务状态分布统计（assigned/inProgress/done/blocked/review 计数）。
     *
     * <p>原实现位于 agent 域 AgentStatsService（直捅 SubTaskMapper），阶段五收口到
     * task 域：状态枚举归本域解释，agent 域不再感知 sub_task 表结构。</p>
     *
     * @param agentId Agent ID；null 返回全零分布（与旧行为一致）
     * @return 五类计数 Map（键固定，绝不返回 null）
     */
    Map<String, Integer> countByStatusForAgent(Long agentId);

    /**
     * 指定 Agent 名下的子任务总数（级联删除前统计、详情页关联计数）。
     *
     * @param agentId Agent ID
     * @return 子任务数
     */
    long countByAssignedAgent(Long agentId);

    /**
     * 指定 Agent 作为审查者产生的审查记录数（级联删除前统计、详情页关联计数）。
     *
     * @param agentId Agent ID
     * @return 审查记录数
     */
    long countReviewByReviewerAgent(Long agentId);

    /**
     * 级联删除前解绑：将指定 Agent 名下的子任务 assigned_agent_id 置空。
     *
     * <p>调用方（agent 域级联删除）保持自身事务，本方法以 REQUIRED 传播加入。</p>
     *
     * @param agentId Agent ID
     */
    @Transactional(rollbackFor = Exception.class)
    void unlinkByAssignedAgent(Long agentId);

    /**
     * 查询指定 Agent 在跑子任务列表（ASSIGNED / IN_PROGRESS / REWORK 语义）。
     *
     * <p>原实现位于 agent 域 AgentDutyLeaseServiceImpl（直捅 SubTaskMapper.selectInFlightByAgent），
     * 阶段五收口到 task 域：在跑状态语义归本域解释。</p>
     *
     * @param agentId Agent ID
     * @param limit   最大返回条数（调用方仅做空判断时传 1）
     * @return 在跑子任务列表（可能为空，绝不返回 null）
     */
    List<SubTask> selectInFlightByAgent(Long agentId, int limit);

    /**
     * 指定 Agent 在跑子任务计数（并发额度占用统计）。
     *
     * <p>原实现位于 agent 域 InFlightDbQuotaService（直捅 SubTaskMapper.countInFlightByAgent），
     * 阶段五收口到 task 域。</p>
     *
     * @param agentId Agent ID
     * @return 在跑子任务数
     */
    int countInFlightByAgent(Long agentId);

    /**
     * 原子认领子任务（并发安全：DB 条件更新 WHERE status='PENDING' 且 assigned 为空或本人）。
     *
     * <p>原实现位于 agent 域 McpToolServiceImpl（直捅 SubTaskMapper.claimAtomic），
     * 阶段五收口到 task 域，agent 域只依赖本方法契约。</p>
     *
     * @param subTaskId 子任务 ID
     * @param agentId   认领 Agent ID
     * @return true=认领成功；false=已被他人抢走或状态已变
     */
    @Transactional(rollbackFor = Exception.class)
    boolean claimAtomic(Long subTaskId, Long agentId);
}
