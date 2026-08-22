package com.helloai.api.interceptor;

import com.helloai.common.base.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 授权拦截器：校验已通过认证的请求是否具备 admin 身份。
 * <p>
 * 认证（你是谁）与授权（你能干什么）分离：{@link AuthInterceptor} 只负责认证并写入
 * {@code _authType}，本拦截器在其之后对 {@code /api/admin/**} 路径强制要求
 * {@code _authType == "admin"}，agent 身份（外部 AI 的 API Key）一律 403。
 * 新增任何 admin 端点只要落在该路径前缀下即自动被覆盖，无需逐端点注解。
 */
public class AdminOnlyInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object type = request.getAttribute(AuthInterceptor.AUTH_TYPE_KEY);
        if (!"admin".equals(type)) {
            throw new BizException(403, "需要管理员权限");
        }
        return true;
    }
}
