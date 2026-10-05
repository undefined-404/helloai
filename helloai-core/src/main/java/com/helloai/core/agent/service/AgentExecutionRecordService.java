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
     * 子任务是否存在 PENDING/RUNNING 记录（防重复消费）。
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
}
