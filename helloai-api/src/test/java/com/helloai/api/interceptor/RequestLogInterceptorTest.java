package com.helloai.api.interceptor;

import com.helloai.core.system.mapper.RequestLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0 C4：{@link RequestLogInterceptor} 的 MDC 业务标识行为测试。
 *
 * <p>覆盖：请求头 X-Run-Id / X-Task-Id / X-Step-Id / X-Trace-Id 写入 MDC、空白头跳过、
 * traceId 自动生成、afterCompletion 统一清理。纯 Mockito + MockHttpServletRequest，不启动容器。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RequestLogInterceptor(MDC 业务标识)")
class RequestLogInterceptorTest {

    @Mock
    private RequestLogMapper requestLogMapper;

    private RequestLogInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new RequestLogInterceptor(requestLogMapper);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("preHandle：四个业务头全部写入 MDC")
    void shouldPutAllHeadersIntoMdc() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/demo");
        request.addHeader("X-Trace-Id", "trace-1");
        request.addHeader("X-Run-Id", "run-1-1");
        request.addHeader("X-Task-Id", "42");
        request.addHeader("X-Step-Id", "2");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(MDC.get("traceId")).isEqualTo("trace-1");
        assertThat(MDC.get("run_id")).isEqualTo("run-1-1");
        assertThat(MDC.get("task_id")).isEqualTo("42");
        assertThat(MDC.get("step_id")).isEqualTo("2");
    }

    @Test
    @DisplayName("无 X-Trace-Id：自动生成 traceId 并写入")
    void shouldGenerateTraceIdWhenHeaderMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/demo");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(MDC.get("traceId")).isNotBlank();
    }

    @Test
    @DisplayName("空白业务头：跳过写入（可选标识不强制）")
    void shouldSkipBlankHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/demo");
        request.addHeader("X-Run-Id", "   ");
        request.addHeader("X-Task-Id", "");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(MDC.get("run_id")).isNull();
        assertThat(MDC.get("task_id")).isNull();
    }

    @Test
    @DisplayName("afterCompletion：请求级 MDC 键统一清理（业务头 + traceId）")
    void shouldClearAllMdcKeysAfterCompletion() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/demo");
        request.addHeader("X-Trace-Id", "trace-2");
        request.addHeader("X-Run-Id", "run-2-1");
        request.addHeader("X-Task-Id", "7");
        request.addHeader("X-Step-Id", "3");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);

        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("run_id")).isNull();
        assertThat(MDC.get("task_id")).isNull();
        assertThat(MDC.get("step_id")).isNull();
    }
}