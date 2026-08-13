package com.helloai.core.task.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.dispatcher.ResilientDispatcher;
import com.helloai.core.task.entity.SubTask;
import lombok.Data;
import org.springframework.transaction.annotation.Transactional;

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
     */
    SubTask getByIdForUpdate(Long subTaskId);

    /**
     * ready 语义判定（V27 内循环依赖编排）：{@code depends_on} 中所有前置子任务
     * 均为 DONE 才允许分发；空依赖直接就绪（旧数据行为与现状完全一致）。
     *
     * <p>分发链两处复用：{@code SubTaskDispatchService.dispatchPendingSubTaskAuto}
     * 与 {@code SubTaskPendingOrphanTask} 孤儿扫描，依赖检查逻辑收敛在本方法。</p>
     */
    boolean isReady(SubTask subTask);

    /**
     * 写入依赖 id 数组（V27）：手工拼 JSON 数字数组后走专用 Mapper SQL（::jsonb），
     * 不走 updateById 全列覆盖，避免乐观锁 version 参数依赖。
     * 专供 PlannerAnalysisService 拆解落库后的"序号→真实 id"回写。
     *
     * <p>不能用全局 ObjectMapper 序列化：它注册了 Long→String（JacksonConfig，
     * 防前端精度丢失），会把依赖 id 写成字符串数组，导致 ready 守卫读取时
     * 归一化失败、有依赖节点被误判为就绪。</p>
     */
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
     * A0-4（§6.63）：驳回统一补发收件箱通知（自动核验 rejectAndRework 与人工驳回 rework/reworkFresh 共用），
     * 摘要携带最近一轮 review 结果（评分/评语/问题），外部 Agent 轮询 pullTasks 即可感知返工原因。
     * 发送失败只 warn 不阻断（返工主链路优先）。
     */
    @Transactional(rollbackFor = Exception.class)
    void rework(Long subTaskId, Long reworkAgentId);

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
    void markManualIntervention(Long subTaskId, String reason, Map<String, Object> extra);

    @Transactional(rollbackFor = Exception.class)
    void block(Long subTaskId);

    @Transactional(rollbackFor = Exception.class)
    void block(Long subTaskId, String reason, Long reporterAgentId);

    @Transactional(rollbackFor = Exception.class)
    void cancel(Long subTaskId);

    /**
     * 将 PENDING 子任务分配给指定 Agent（v2.4 §4.5 熔断调度入口）。
     *
     * <p><b>⚠️ 调用约束：本方法只能由 {@link ResilientDispatcher} 调用！</b>
     * 业务方必须走 {@code resilientDispatcher.assignNext(agentId, subTaskId)}，
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
     * 会清空当前 assignedAgent，让后续 {@link ResilientDispatcher#assignNext(Long, Long)}
     * 重新发布标准 ASSIGNED 事件与自动执行链。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    SubTask resetToPendingForDispatch(Long subTaskId, Set<SubTaskStatus> allowedStatuses);

    @Transactional(rollbackFor = Exception.class)
    void reassign(Long subTaskId, Long newAgentId);

    /**
     * 批量创建子任务（v2.5 M4.5 派发控制台——同内容 fan-out 派给多个 Agent）。
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
     * 批量创建单项参数（v2.5 M4.5）。
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
}
