package com.helloai.core.system.entity;

import com.helloai.core.shared.handler.PgJsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "request_log", autoResultMap = true)
public class RequestLog extends BaseEntity {

    private String requestId;
    private String method;
    private String path;

    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private Map<String, Object> params;

    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private Map<String, Object> response;

    private Integer duration;
    private String ip;
    private Integer statusCode;
    private String authType;
    private Long authId;
}
