package com.helloai.api.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import com.helloai.common.base.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 授权拦截器：校验已通过认证的请求是否具备 admin 身份。
 * <p>
 * 认证（你是谁）与授权（你能干什么）分离：{@link AuthInterceptor} 只负责认证并写入
 * {@code _authType}，本拦截器在其之后对 {@code /api/admin/**} 路径强制要求管理身份。
 * 新增任何 admin 端点只要落在该路径前缀下即自动被覆盖，无需逐端点注解。
 *
 * <p><b>事实源为 Sa-Token 登录态 + {@code sys_user_role} 角色码</b>（BASE-4.1）：
 * 不再依赖认证阶段手写的 {@code _authType} request attribute——该 attribute 仅供
 * Controller 读取身份信息（如 {@code AuthController#changePassword}），不作授权依据。
 * 外部 Agent（API Key）不进入 Sa-Token 会话体系，{@code isLogin()} 为 false → 403。</p>
 */
public class AdminOnlyInterceptor implements HandlerInterceptor {

    /** 平台管理角色码：任一命中即视为管理身份（SUPER_ADMIN 在权限侧为 "*" 通配）。 */
    private static final String[] ADMIN_ROLE_CODES = {"SUPER_ADMIN", "ADMIN"};

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!StpUtil.isLogin() || !StpUtil.hasRoleOr(ADMIN_ROLE_CODES)) {
            throw new BizException(403, "需要管理员权限");
        }
        return true;
    }
}
