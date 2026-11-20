package com.helloai.core.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.ExecutionStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_execution_record")
public class AgentExecutionRecord extends BaseEntity {

    private String eventId;
    private Long subTaskId;
    private ExecutionStatus status;
    private String workerNode;
    private OffsetDateTime startTime;
    private OffsetDateTime endTime;
    private String errorMsg;
    private Integer retryCount;

    /**
     * 本次执行 Token 用量（B5 token 成本观测）：loop 全部轮次 totalTokens 累加；
     * NULL = provider 未返回 usage 或未统计（best-effort，不阻断执行）。
     */
    private Integer tokenUsage;

    /**
     * 命令触发来源：assigned / reassigned / retry / poll-recovery。
     * <p>冗余存储：便于 DB Poller 恢复 ExecutionCommand 时还原调度上下文。</p>
     */
    private String triggerType;

    /**
     * 目标 Agent ID。
     * <p>冗余存储：便于 DB Poller 在不查 Agent 表的情况下恢复 ExecutionCommand.agentId。</p>
     */
    private Long agentId;

    /**
     * 目标 Agent 接入类型。
     * <p>冗余存储：便于 DB Poller 在不查 Agent 表的情况下恢复 ExecutionCommand.accessType。</p>
     */
    private AgentAccessType accessType;

    /**
     * DB Poller 最近一次扫描该行的时间。
     * <p>NULL 表示尚未被 Poller 触及过。</p>
     * <p>扫描条件：{@code status='PENDING' AND (last_attempt_time IS NULL OR last_attempt_time < now - threshold)}。</p>
     */
    private OffsetDateTime lastAttemptTime;

    /**
     * 最近一次「有进展」的时间（`MA-10` 改动 3，V153 新增列）。
     *
     * <p><b>为什么必须是独立一列，不能复用 {@link #lastAttemptTime}</b>：后者语义是「DB Poller
     * 最近一次扫描该行的时间」（PENDING 扫描闸用），与「执行是否还在推进」无关。
     * 复用会造成两件事绑死：放宽 PENDING 扫描节奏就等于放宽进展判定，反之亦然。</p>
     *
     * <p><b>写点</b>：{@code ExecutionProgressRecorder} 在每轮末（{@code LoopCheckpointListener} 回调）
     * 与工具调用边界更新（best-effort，失败只 warn）。</p>
     *
     * <p><b>读点</b>：{@code ExecutionCompensationTask} 的 RUNNING 扫描改为
     * {@code last_progress_at < now - noProgressTimeoutMinutes}（{@code NULL} 回落 {@code start_time}，
     * 保证存量行行为与改造前一致）。</p>
     */
    private OffsetDateTime lastProgressAt;

    /**
     * 乐观锁版本号。
     * <p>mark* 系列改为 {@code update(entity, wrapper)} + {@code eq(status, oldStatus)} 双条件 CAS 后，
     * version 比较与自增交由 MyBatis-Plus OptimisticLockerInnerInterceptor 自动处理（规范 §15），
     * 禁止手写 {@code version = version + 1} SQL。</p>
     */
    @Version
    private Integer version;
}
