package com.helloai.core.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.OutboxStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_outbox_event")
public class AgentOutboxEvent extends BaseEntity {

    private String eventId;
    private String eventType;
    private String routingKey;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;

    private OutboxStatus status;
    private Integer retryCount;
    private String errorMsg;
    private OffsetDateTime nextRetryTime;
}
