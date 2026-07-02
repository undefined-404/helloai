package com.helloai.api.interceptor;

import com.helloai.core.entity.RequestLog;
import com.helloai.core.mapper.RequestLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.UUID;

public class RequestLogInterceptor implements HandlerInterceptor {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String START_TIME_KEY = "_startTime";

    private final RequestLogMapper requestLogMapper;

    public RequestLogInterceptor(RequestLogMapper requestLogMapper) {
        this.requestLogMapper = requestLogMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(TRACE_ID_KEY, traceId);
        request.setAttribute(START_TIME_KEY, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                 Object handler, Exception ex) {
        try {
            Long startTime = (Long) request.getAttribute(START_TIME_KEY);
            if (startTime == null) return;

            int duration = (int) (System.currentTimeMillis() - startTime);
            String path = request.getRequestURI();
            String method = request.getMethod();

            // 只记录 /api/ 请求
            if (!path.startsWith("/api/")) return;

            RequestLog log = new RequestLog();
            log.setRequestId(MDC.get(TRACE_ID_KEY));
            log.setMethod(method);
            log.setPath(path);
            log.setParams(Map.of(
                    "query", request.getQueryString() != null ? request.getQueryString() : ""
            ));
            log.setDuration(duration);
            log.setIp(request.getRemoteAddr());
            log.setStatusCode(response.getStatus());
            log.setAuthType((String) request.getAttribute(AuthInterceptor.AUTH_TYPE_KEY));
            log.setAuthId((Long) request.getAttribute(AuthInterceptor.AUTH_ID_KEY));

            requestLogMapper.insert(log);
        } catch (Exception e) {
            // 日志记录异常不应影响主流程
            MDC.remove(TRACE_ID_KEY);
        } finally {
            MDC.remove(TRACE_ID_KEY);
        }
    }
}
