package com.helloai.core.agent.entity;

import com.helloai.core.shared.handler.PgJsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.OutboxStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "agent_outbox_event", autoResultMap = true)
public class AgentOutboxEvent extends BaseEntity {

    private String eventId;
    private String eventType;
    private String routingKey;

    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private Map<String, Object> payload;

    private OutboxStatus status;
    private Integer retryCount;
    private String errorMsg;
    private OffsetDateTime nextRetryTime;
}
