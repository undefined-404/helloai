package com.helloai.api.interceptor;

import com.helloai.core.system.service.RequestLogService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.UUID;

public class RequestLogInterceptor implements HandlerInterceptor {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String RUN_ID_KEY = "run_id";
    private static final String TASK_ID_KEY = "task_id";
    private static final String STEP_ID_KEY = "step_id";
    private static final String START_TIME_KEY = "_startTime";

    /** 请求级 MDC 键集：preHandle 写入、afterCompletion 统一清理（含事件链业务键）。 */
    private static final String[] MDC_REQUEST_KEYS = {TRACE_ID_KEY, RUN_ID_KEY, TASK_ID_KEY, STEP_ID_KEY};

    private final RequestLogService requestLogService;

    public RequestLogInterceptor(RequestLogService requestLogService) {
        this.requestLogService = requestLogService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 异步接口（SseEmitter 等）的 ASYNC 二次分派：不重复 put MDC / 覆盖 START_TIME——
        // traceId 与计时沿用 REQUEST 阶段值，afterCompletion 在 ASYNC 结束统一落一条日志。
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(TRACE_ID_KEY, traceId);
        // 事件链业务标识入 MDC（下游执行链/RabbitMQ 消费端按同键续传，无头则跳过不影响主流程）
        putHeaderIfPresent(request, "X-Run-Id", RUN_ID_KEY);
        putHeaderIfPresent(request, "X-Task-Id", TASK_ID_KEY);
        putHeaderIfPresent(request, "X-Step-Id", STEP_ID_KEY);
        request.setAttribute(START_TIME_KEY, System.currentTimeMillis());
        return true;
    }

    /** 请求头非空时写入 MDC；头缺失/空白时跳过（可选业务标识，不强制）。 */
    private void putHeaderIfPresent(HttpServletRequest request, String header, String mdcKey) {
        String value = request.getHeader(header);
        if (value != null && !value.isBlank()) {
            MDC.put(mdcKey, value);
        }
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

            // 落库走 system 域 Service 契约（§7.1：跨域不得直捅 Mapper；
            // 实体与表结构由 system 域持有，本层只传基础类型快照）
            requestLogService.record(
                    MDC.get(TRACE_ID_KEY),
                    method,
                    path,
                    Map.of("query", request.getQueryString() != null ? request.getQueryString() : ""),
                    duration,
                    request.getRemoteAddr(),
                    response.getStatus(),
                    (String) request.getAttribute(AuthInterceptor.AUTH_TYPE_KEY),
                    (Long) request.getAttribute(AuthInterceptor.AUTH_ID_KEY));
        } catch (Exception e) {
            // 日志记录异常不应影响主流程
        } finally {
            for (String key : MDC_REQUEST_KEYS) {
                MDC.remove(key);
            }
        }
    }
}
