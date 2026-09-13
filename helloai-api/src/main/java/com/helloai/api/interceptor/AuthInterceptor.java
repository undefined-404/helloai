package com.helloai.api.interceptor;

import com.helloai.common.base.BizException;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.port.AgentAuthPort;
import com.helloai.core.system.service.AuthService;
import jakarta.servlet.DispatcherType;
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
    private final AgentAuthPort agentAuthPort;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 异步接口（SseEmitter 等）会触发容器 ASYNC 二次分派：同一请求的 REQUEST 阶段已完成鉴权，
        // 而异步线程没有 Sa-Token ThreadLocal 上下文（Sa-Token 1.44 Context Filter 仅注册 REQUEST，
        // 官方 v1.46 才补 ASYNC），此处直接放行，避免 SaTokenContextException 500 刷屏。
        // 语义 = 官方 v1.46「Context Filter 覆盖 ASYNC」：异步分派不再重复鉴权。
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }

        // ① 平台账号通道：Sa-Token 会话（token 走 X-Admin-Token 头）
        //    守门走标准 StpUtil.checkLogin，方能使 active-timeout 校验与滑动续期生效
        String adminToken = request.getHeader("X-Admin-Token");
        if (adminToken != null && !adminToken.isBlank()) {
            AuthService.AdminSession session = authService.authenticateAdmin(adminToken);
            request.setAttribute(AUTH_TYPE_KEY, "admin");
            request.setAttribute(AUTH_ID_KEY, session.id());
            request.setAttribute(AUTH_NAME_KEY, session.displayName());
            return true;
        }

        // ② 外部 Agent 通道：API Key（Bearer）——显式旁路，不进入 Sa-Token 会话体系
        //    §10 红线：CLI_CLIENT 走 API Key / MCP，契约不变
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String apiKey = authorization.substring(7);
            if (!apiKey.isBlank()) {
                Agent agent = agentAuthPort.validateApiKey(apiKey);
                request.setAttribute(AUTH_TYPE_KEY, "agent");
                request.setAttribute(AUTH_ID_KEY, agent.getId());
                request.setAttribute(AUTH_NAME_KEY, agent.getName());
                return true;
            }
        }

        // ③ 无认证信息
        throw new BizException(401, "未登录或凭证已过期");
    }
}
