package com.helloai.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
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
}
