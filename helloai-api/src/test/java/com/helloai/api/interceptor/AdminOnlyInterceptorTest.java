package com.helloai.api.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import com.helloai.common.base.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * AdminOnlyInterceptor 单测：平台账号路径限定（{@code /api/admin/**}）。
 *
 * <p>BASE-4.4 起语义修订为<strong>只区分平台账号与外部 Agent</strong>，不再判角色
 * （授权交给动作级权限码，见 CODE_STYLE §43）：</p>
 *
 * <ul>
 *   <li>平台账号会话（任意角色，含 NORMAL_USER / GUEST）→ 放行</li>
 *   <li>未登录（含外部 Agent API Key：不进 Sa-Token 会话体系）→ 403</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminOnlyInterceptor 平台账号路径限定")
class AdminOnlyInterceptorTest {

    private final AdminOnlyInterceptor interceptor = new AdminOnlyInterceptor();

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Test
    @DisplayName("平台账号（已登录）放行")
    void loggedInPasses() throws Exception {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::isLogin).thenReturn(true);

            assertTrue(interceptor.preHandle(request, response, new Object()));
        }
    }

    @Test
    @DisplayName("未登录（含外部 Agent API Key）返回 403")
    void notLoginRejected() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::isLogin).thenReturn(false);

            BizException ex = assertThrows(BizException.class,
                    () -> interceptor.preHandle(request, response, new Object()));
            assertEquals(403, ex.getCode());
        }
    }
}
