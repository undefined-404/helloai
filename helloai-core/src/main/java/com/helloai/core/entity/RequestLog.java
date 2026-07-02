package com.helloai.core.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("request_log")
public class RequestLog extends BaseEntity {

    private String requestId;
    private String method;
    private String path;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> params;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> response;

    private Integer duration;
    private String ip;
    private Integer statusCode;
    private String authType;
    private Long authId;
}
