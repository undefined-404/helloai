package com.helloai.api.interceptor;

import com.helloai.common.base.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * AdminOnlyInterceptor 单测：认证与授权分离中的授权分支。
 *
 * <ul>
 *   <li>admin 身份放行</li>
 *   <li>agent 身份（外部 AI API Key）一律 403</li>
 *   <li>_authType 缺失（理论上认证阶段已拦截，此处兜底）同样 403</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminOnlyInterceptor admin 授权拦截")
class AdminOnlyInterceptorTest {

    private final AdminOnlyInterceptor interceptor = new AdminOnlyInterceptor();

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Test
    @DisplayName("admin 身份放行")
    void adminPasses() throws Exception {
        when(request.getAttribute(AuthInterceptor.AUTH_TYPE_KEY)).thenReturn("admin");
        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    @DisplayName("agent 身份访问 admin 端点返回 403")
    void agentRejected() {
        when(request.getAttribute(AuthInterceptor.AUTH_TYPE_KEY)).thenReturn("agent");
        BizException ex = assertThrows(BizException.class,
                () -> interceptor.preHandle(request, response, new Object()));
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("缺失 _authType 属性返回 403")
    void missingAuthTypeRejected() {
        when(request.getAttribute(AuthInterceptor.AUTH_TYPE_KEY)).thenReturn(null);
        BizException ex = assertThrows(BizException.class,
                () -> interceptor.preHandle(request, response, new Object()));
        assertEquals(403, ex.getCode());
    }
}
