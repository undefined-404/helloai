package com.helloai.api.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SetupLockInterceptor 单测（REF-5.3）：
 *
 * <ul>
 *   <li>无用户 ⇒ 拒绝：503 + {@code data.setup_required=true}（服务未就绪，非权限问题）</li>
 *   <li>有用户 ⇒ 放行，且**闩锁**：此后不再查用户数（热路径零开销）</li>
 *   <li>闩锁是单向的：先拒后放行后，再拒不了（见拦截器 javadoc 的诚实边界）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SetupLockInterceptor 首次运行锁定")
class SetupLockInterceptorTest {

    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final StringWriter body = new StringWriter();

    /** 用户数替身：可变更的计数器，同时统计被查询次数。 */
    private final AtomicInteger userCount = new AtomicInteger(0);
    private final AtomicInteger queries = new AtomicInteger(0);

    private SetupLockInterceptor newInterceptor() {
        return new SetupLockInterceptor(() -> {
            queries.incrementAndGet();
            return userCount.get();
        }, new ObjectMapper());
    }

    private HttpServletResponse responseCapturingBody() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body, true));
        return response;
    }

    @Test
    @DisplayName("无用户 → 503 + setup_required=true（不依赖 system.setup_finished，该值在 V1 被预置为 1）")
    void blocksWhenNoUser() throws Exception {
        HttpServletResponse response = responseCapturingBody();
        userCount.set(0);

        boolean proceed = newInterceptor().preHandle(request, response, new Object());

        assertFalse(proceed, "未初始化实例不应放行");
        org.mockito.Mockito.verify(response).setStatus(anyInt());
        String json = body.toString();
        assertTrue(json.contains("\"setup_required\":true"), "响应体应可自解释: " + json);
        assertTrue(json.contains("503"), "响应体应带 503: " + json);
    }

    @Test
    @DisplayName("有用户 → 放行，且闩锁生效（第二次不再查库）")
    void passesAndLatchesWhenUserExists() throws Exception {
        SetupLockInterceptor interceptor = newInterceptor();
        userCount.set(1);
        // 放行路径不写响应体：用裸 mock（严格打桩下，未被使用的 getWriter 打桩会报 UnnecessaryStubbing）
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertTrue(interceptor.preHandle(request, response, new Object()));

        assertEquals(1, queries.get(), "闩锁后不应再查用户数（热路径零开销）");
    }

    @Test
    @DisplayName("闩锁单向：一旦放行过，用户数归零也不再拦（诚实边界，恢复需重启）")
    void latchIsMonotonic() throws Exception {
        SetupLockInterceptor interceptor = newInterceptor();
        HttpServletResponse response = mock(HttpServletResponse.class);

        userCount.set(1);
        assertTrue(interceptor.preHandle(request, response, new Object()));

        userCount.set(0);
        assertTrue(interceptor.preHandle(request, response, new Object()), "闩锁为单向语义");
    }
}
