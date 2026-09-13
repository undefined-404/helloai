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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

/**
 * AdminOnlyInterceptor 单测：认证与授权分离中的授权分支。
 *
 * <p>BASE-4.1 起事实源为 Sa-Token 登录态 + {@code sys_user_role} 角色码：</p>
 *
 * <ul>
 *   <li>管理身份（SUPER_ADMIN / ADMIN）放行</li>
 *   <li>已登录但无管理角色（如 NORMAL_USER / GUEST）→ 403</li>
 *   <li>未登录（含外部 Agent API Key，不进 Sa-Token 会话体系）→ 403</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminOnlyInterceptor 管理身份授权拦截")
class AdminOnlyInterceptorTest {

    private final AdminOnlyInterceptor interceptor = new AdminOnlyInterceptor();

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Test
    @DisplayName("管理身份（isLogin + 命中管理角色）放行")
    void adminPasses() throws Exception {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::isLogin).thenReturn(true);
            stp.when(() -> StpUtil.hasRoleOr(any(String[].class))).thenReturn(true);

            assertTrue(interceptor.preHandle(request, response, new Object()));
        }
    }

    @Test
    @DisplayName("已登录但无管理角色返回 403")
    void loggedInWithoutAdminRoleRejected() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::isLogin).thenReturn(true);
            stp.when(() -> StpUtil.hasRoleOr(any(String[].class))).thenReturn(false);

            BizException ex = assertThrows(BizException.class,
                    () -> interceptor.preHandle(request, response, new Object()));
            assertEquals(403, ex.getCode());
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
