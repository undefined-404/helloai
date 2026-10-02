package com.helloai.core.system.service;

import java.util.Map;

/**
 * 请求日志服务（B1：api 去 Mapper 直连）。
 *
 * <p>请求台账（{@link com.helloai.core.system.entity.RequestLog}）的写入能力，
 * 供 Web 层拦截器（{@code helloai-api}）跨域调用。</p>
 *
 * <p><b>契约口径</b>：形参取基础类型与 {@code Map}，<b>不暴露实体</b>——消费方无需
 * 感知 {@code request_log} 表结构，字段增删在本域内收口（§7.2 值对象不泄漏实体）。</p>
 *
 * <p><b>失败语义</b>：本方法自身不吞异常；「日志失败不阻断主流程」的兜底由调用方
 * （拦截器）承担，服务层保持 fail-fast，便于单测直接断言异常行为。</p>
 */
public interface RequestLogService {

    /**
     * 记录一条请求日志。
     *
     * @param requestId  链路追踪 ID（MDC traceId），允许 null
     * @param method     HTTP 方法（GET / POST ...）
     * @param path       请求路径
     * @param params     请求参数快照（查询串等），允许 null
     * @param duration   耗时（毫秒）
     * @param ip         客户端 IP
     * @param statusCode 响应状态码
     * @param authType   认证通道类型（admin / agent / 匿名），允许 null
     * @param authId     认证主体 ID（管理员 ID 或 Agent ID），允许 null
     */
    void record(String requestId, String method, String path, Map<String, Object> params,
                Integer duration, String ip, Integer statusCode, String authType, Long authId);
}
