package com.helloai.api.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import com.helloai.core.shared.util.UserContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 请求级用户上下文写入（PA-2a）。
 *
 * <p>在请求进入业务方法前，把当前登录用户 id 写入 {@link UserContext}（ThreadLocal），
 * 供 {@code core.shared.util.CurrentUser} 在任意线程（含被 {@code TaskDecorator} 装饰的
 * 异步线程）读取；请求完成 / 异常时清理，避免跨请求串号。</p>
 *
 * <p><b>ASYNC 二次分派直接放行</b>：SseEmitter 等异步接口触发容器 ASYNC RE-dispatch 时，
 * 请求线程的 UserContext 已在首次 REQUEST 阶段写入并随 TaskDecorator 复制给异步线程；
 * 二次分派不重复写入（也无 Sa-Token 上下文）。与 {@code AuthInterceptor} 同模式。</p>
 *
 * <p><b>取不到不阻断</b>：外部 Agent（API Key）通道、未登录白名单路径取不到登录用户，
 * 此时依赖 Sa-Token 回退或 null 宽松并入，业务照常。</p>
 */
public class UserContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }
        Long userId = null;
        try {
            userId = StpUtil.getLoginIdAsLong();
        } catch (Exception ignore) {
            // 无登录上下文（外部 Agent / 未登录白名单）：置 null，不影响业务
        }
        UserContext.set(userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }
}