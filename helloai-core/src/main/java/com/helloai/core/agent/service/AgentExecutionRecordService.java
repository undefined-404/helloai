package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.core.agent.entity.AgentExecutionRecord;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Agent 执行记录服务（agent_execution_record 表）。
 * 覆盖执行命令的 PENDING/RUNNING/SUCCESS/FAILED/TIMEOUT 状态推进与 DB Poller 扫描。
 */
public interface AgentExecutionRecordService extends IService<AgentExecutionRecord> {

    /**
     * 创建 PENDING 执行记录（冗余存储 trigger / agentId / accessType，便于 Poller 不查 Agent 表恢复）。
     */
    AgentExecutionRecord createPending(String eventId, Long subTaskId,
                                       Long agentId, AgentAccessType accessType, String trigger);

    /**
     * PENDING → RUNNING（CAS）。
     */
    boolean markRunning(Long id);

    /**
     * RUNNING → SUCCESS（CAS）。
     */
    boolean markSuccess(Long id);

    /**
     * RUNNING → SUCCESS（CAS，落 Token 用量：loop 全部轮次 totalTokens 累加，可为 null）。
     */
    boolean markSuccess(Long id, Integer tokenUsage);

    /**
     * RUNNING → FAILED（CAS，errorMsg 截断 500 字符）。
     */
    boolean markFailed(Long id, String errorMsg);

    /**
     * RUNNING → FAILED（CAS，errorMsg 截断 500 字符，落 Token 用量可为 null）。
     */
    boolean markFailed(Long id, String errorMsg, Integer tokenUsage);

    /**
     * PENDING/RUNNING → TIMEOUT。
     */
    boolean markTimeout(Long id);

    /**
     * 收口子任务<b>在飞</b>的平台执行记录（PENDING/RUNNING → FAILED，落 {@code errorMsg}）。
     *
     * <p><b>为什么需要（2026-10-25 实测 2113947248843329537）</b>：人工把停滞子任务
     * {@code block()} 后改派，{@code SubTaskAutoExecutionDispatcher} 建命令时被
     * {@link #hasPendingOrRunning} 挡下——旧记录仍是 {@code RUNNING}（模型进程还在跑，4 分 45 秒），
     * 于是「改派」100% 失败，子任务停在 ASSIGNED 无命令可消费，只能干等 10 分钟
     * {@code AssignedSubTaskTimeoutTask} 回收；回收后依然被同一条记录挡住 ⇒ 二次失败。</p>
     *
     * <p><b>口径与 {@link #hasPendingOrRunning} 完全一致</b>（只动 {@code access_type}
     * 为空或 {@link AgentAccessType#API_KEY_LLM} 的行）：外部路径的 attempt 记录不对应任何平台命令，
     * 不在平台可收口范围内。两处必须共用同一匹配条件，否则「判有没有」和「清不清得掉」会分叉。</p>
     *
     * <p><b>语义 = 「这次执行的结果作废」</b>，不是「执行失败」。旧进程（若有）仍会跑完，
     * 其终态写入被 {@code ExecutionStatus} 的 CAS（只从 RUNNING 迁移）拒绝，
     * 结果回写被 {@code sub_task} 状态守卫丢弃（{@code subtask_status_not_in_progress}），
     * 与既有的超时补偿路径同一套语义，不引入新分支。</p>
     *
     * @param subTaskId 子任务 ID
     * @param reason    作废原因（落 {@code error_msg}，截断 500 字符）
     * @return 实际收口的记录条数；0 表示本来就没有在飞记录
     */
    int closeInFlight(Long subTaskId, String reason);

    /**
     * 子任务是否存在<b>平台内</b>的 PENDING/RUNNING 记录（防重复消费）。
     *
     * <p>只统计 {@code access_type} 为空或 {@link AgentAccessType#API_KEY_LLM} 的行：
     * 外部路径的 attempt 记录由认领动作写入，不对应任何平台执行命令，
     * 不应参与「重复发命令」与「重复执行」的防重判定（详见实现类注释）。</p>
     */
    boolean hasPendingOrRunning(Long subTaskId);

    /**
     * DB Poller 扫描：查找「长时间未被消费的 PENDING」记录（孤儿）。
     */
    List<AgentExecutionRecord> listOrphanPending(int thresholdSeconds, int limit);

    /**
     * 旧 DB Poller 主消费扫描：查找「所有未被消费的 PENDING」记录。
     *
     * @deprecated  Poller 不再调用本方法；保留仅为兼容历史代码与排查工具。
     *             新代码请使用 {@link #listOrphanPending(int, int)}。
     */
    @Deprecated
    List<AgentExecutionRecord> listAllPending(int limit);

    /**
     * DB Poller 触及痕迹：更新 last_attempt_at 为当前时间（不限制 status）。
     */
    boolean markPolled(Long id);

    // ══════════════════════════════════════════════════════════════
    //  §7.1 helloai-job 去 Mapper 直连收口（补偿扫描出口）
    // ══════════════════════════════════════════════════════════════

    /**
     * 扫描指定状态且 create_time 早于 before 的执行记录（补偿扫描：PENDING 卡死）。
     *
     * <p>承接 helloai-job {@code ExecutionCompensationTask} 直捅
     * {@code AgentExecutionRecordMapper.selectByStatusAndCreateTimeBefore}，SQL 语义不变。</p>
     *
     * @param status 目标状态
     * @param before create_time &lt; before 视为超时
     * @return 命中记录列表（可能为空，绝不返回 null）
     */
    List<AgentExecutionRecord> listByStatusCreatedBefore(ExecutionStatus status, OffsetDateTime before);

    /**
     * 扫描指定状态且 start_time 早于 before 的执行记录（补偿扫描：RUNNING 卡死）。
     *
     * <p>承接 helloai-job {@code ExecutionCompensationTask} 直捅
     * {@code AgentExecutionRecordMapper.selectByStatusAndStartTimeBefore}，SQL 语义不变。</p>
     *
     * @param status 目标状态
     * @param before start_time &lt; before 视为超时
     * @return 命中记录列表（可能为空，绝不返回 null）
     */
    List<AgentExecutionRecord> listByStatusStartedBefore(ExecutionStatus status, OffsetDateTime before);

    // ══════════════════════════════════════════════════════════════
    //  B5.2 Fleet 成本选人：成本画像聚合（agent_execution_record 派生）
    // ══════════════════════════════════════════════════════════════

    /**
     * 某 Agent「最近 {@code limit} 次成功执行」的 token 均值（成本画像原料）。
     *
     * <p>B5.3 选人链（{@code AgentSelector}）按候选逐个调用，与质量画像
     * {@code AgentQualityProfileService.computeQualityScore} 的调用形态一致。</p>
     *
     * <p><b>best-effort 契约</b>：查询异常一律返回 {@code null}（等同于「无成本数据」），
     * 绝不向调度主链路抛出——选人不可因成本画像故障而失败。返回值语义：</p>
     * <ul>
     *   <li>{@code null} = 无样本（该 Agent 无成功执行记录 / 全部 {@code token_usage} 为 NULL / 查询异常）</li>
     *   <li>{@code >= 0} = 均值（四舍五入取整）</li>
     * </ul>
     *
     * @param agentId 目标 Agent；为 null 返回 null
     * @param limit   取样条数（最近 N 次成功执行）；&le; 0 时按默认 1 处理
     */
    Integer averageRecentSuccessTokens(Long agentId, int limit);

    // ══════════════════════════════════════════════════════════════
    //  G-006 外部执行轨迹对齐：外部路径 attempt 生命周期
    // ══════════════════════════════════════════════════════════════

    /**
     * 开启一次<b>外部</b>执行 attempt（直接落 {@code RUNNING}），幂等。
     *
     * <p><b>为什么要这个方法</b>：{@code agent_execution_record} 此前只由平台内 MQ 派发链
     * （{@link #createPending} → {@link #markRunning}）写入，外部 Agent（CLI_CLIENT /
     * WEB_BROWSER）认领后本地执行、平台侧无对应行 ⇒ 成本画像、执行审计、
     * 「同子任务是否仍有人在跑」三类判断对外部路径全部为空白。</p>
     *
     * <p><b>为什么直接 RUNNING 而不是 PENDING</b>：外部路径没有「命令投递 → 消费」两段，
     * 认领成功那一刻就是执行起点；落 PENDING 会被 {@code ExecutionCommandPoller} 当成
     * 待投递命令重复派发。</p>
     *
     * <p><b>幂等</b>：同一子任务已有 {@code PENDING}/{@code RUNNING} 记录时直接返回既有行
     * （Agent 重连、重复认领、返工后重新开工都不重复计数）。{@code accessType} 为
     * {@link AgentAccessType#API_KEY_LLM} 时不开启（平台内 attempt 由派发链自己建行）。</p>
     *
     * @param subTaskId  子任务
     * @param agentId    执行者
     * @param accessType 执行者接入类型（决定是否属于「外部路径」）
     * @param trigger    触发标记（如 {@code mcp-claim} / {@code mcp-start}）
     * @param workerNode 执行节点标识（外部执行者无平台主机名，传 {@code external:<agentName>}）
     * @return 开启（或已存在）的记录；{@code subTaskId} 为空或不属外部路径时返回 {@code null}
     */
    AgentExecutionRecord openExternalAttempt(Long subTaskId, Long agentId,
                                             AgentAccessType accessType, String trigger, String workerNode);

    /**
     * 收口外部路径的 open attempt（{@code PENDING}/{@code RUNNING} → 终态），只作用于
     * <b>非</b> {@link AgentAccessType#API_KEY_LLM} 的记录。
     *
     * <p>平台内记录由派发链自己的 CAS（{@code applyResultAtomically} /
     * {@code ExecutionCompensationTask}）收尾，本方法不得越界改写它们。</p>
     *
     * @param subTaskId  子任务
     * @param terminal   终态（{@code SUCCESS} / {@code FAILED} / {@code TIMEOUT}）；
     *                   传 {@code PENDING}/{@code RUNNING}/null 视为无效，返回 0
     * @param errorMsg   失败/超时原因（{@code SUCCESS} 时可空）
     * @param tokenUsage 外部执行者回报的 token 用量（可为 null）
     * @return 实际收口条数（0 表示无 open attempt，属正常幂等结果）
     */
    int closeExternalAttempt(Long subTaskId, ExecutionStatus terminal, String errorMsg, Integer tokenUsage);
}
