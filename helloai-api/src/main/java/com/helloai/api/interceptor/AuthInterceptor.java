package com.helloai.api.interceptor;

import com.helloai.common.base.BizException;
import com.helloai.core.entity.Agent;
import com.helloai.core.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.servlet.HandlerInterceptor;

@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    public static final String AUTH_TYPE_KEY = "_authType";
    public static final String AUTH_ID_KEY = "_authId";
    public static final String AUTH_NAME_KEY = "_authName";

    private final AuthService authService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 优先检查管理员 token
        String adminToken = request.getHeader("X-Admin-Token");
        if (adminToken != null && !adminToken.isBlank()) {
            AuthService.AdminSession session = authService.validateAdminToken(adminToken);
            request.setAttribute(AUTH_TYPE_KEY, "admin");
            request.setAttribute(AUTH_ID_KEY, session.id());
            request.setAttribute(AUTH_NAME_KEY, session.displayName());
            return true;
        }

        // 其次检查 Agent API Key
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String apiKey = authorization.substring(7);
            if (!apiKey.isBlank()) {
                Agent agent = authService.validateAgentKey(apiKey);
                request.setAttribute(AUTH_TYPE_KEY, "agent");
                request.setAttribute(AUTH_ID_KEY, agent.getId());
                request.setAttribute(AUTH_NAME_KEY, agent.getName());
                return true;
            }
        }

        // 都没有认证信息
        throw new BizException(401, "未登录或凭证已过期");
    }
}
