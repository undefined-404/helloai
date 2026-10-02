package com.helloai.core.system.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.core.system.entity.RequestLog;
import com.helloai.core.system.mapper.RequestLogMapper;
import com.helloai.core.system.service.RequestLogService;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 请求日志服务实现。
 *
 * <p>本域内唯一持有 {@link RequestLogMapper} 的类：跨域调用方（Web 拦截器）一律经
 * {@link RequestLogService} 契约，不得直捅 Mapper（§7.1 红线）。</p>
 */
@Service
public class RequestLogServiceImpl
        extends ServiceImpl<RequestLogMapper, RequestLog>
        implements RequestLogService {

    @Override
    public void record(String requestId, String method, String path, Map<String, Object> params,
                       Integer duration, String ip, Integer statusCode, String authType, Long authId) {
        RequestLog log = new RequestLog();
        log.setRequestId(requestId);
        log.setMethod(method);
        log.setPath(path);
        log.setParams(params);
        log.setDuration(duration);
        log.setIp(ip);
        log.setStatusCode(statusCode);
        log.setAuthType(authType);
        log.setAuthId(authId);
        save(log);
    }
}
