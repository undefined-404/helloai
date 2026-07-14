package com.helloai.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
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
     * 命令触发来源：assigned / reassigned / retry / poll-recovery。
     * <p>冗余存储：便于 DB Poller 恢复 ExecutionCommand 时还原调度上下文。</p>
     */
    private String trigger;

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
     * <p>扫描条件：{@code status='PENDING' AND (last_attempt_at IS NULL OR last_attempt_at < now - threshold)}。</p>
     */
    private OffsetDateTime lastAttemptAt;
}
