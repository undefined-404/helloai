package com.helloai.api.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import com.helloai.api.interceptor.AdminOnlyInterceptor;
import com.helloai.api.interceptor.AuthInterceptor;
import com.helloai.api.interceptor.RequestLogInterceptor;
import com.helloai.core.agent.port.AgentAuthPort;
import com.helloai.core.system.mapper.RequestLogMapper;
import com.helloai.core.system.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthService authService;
    private final AgentAuthPort agentAuthPort;
    private final RequestLogMapper requestLogMapper;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 请求日志（所有 /api/**）
        registry.addInterceptor(new RequestLogInterceptor(requestLogMapper))
                .addPathPatterns("/api/**");

        // 认证拦截器
        // 通道分流：X-Admin-Token → Sa-Token 会话（标准 checkLogin，含滑动续期）；
        //          Authorization: Bearer → 外部 Agent API Key（显式旁路，不进 Sa-Token）
        registry.addInterceptor(new AuthInterceptor(authService, agentAuthPort))
                .addPathPatterns("/api/**")
                // 登录/登出不需要认证
                .excludePathPatterns("/api/auth/login")
                .excludePathPatterns("/api/auth/logout")
                .excludePathPatterns("/api/auth/me")
                // 自助注册不需要认证（是否开放由 sys_config.auth.register.enabled 在端点内门控）
                .excludePathPatterns("/api/auth/register")
                // Agent 自助注册不需要认证（端点自带 registrationToken 校验 + 注册开关门控）
                .excludePathPatterns("/api/agents/register")
                .excludePathPatterns("/api/agents/registerWithToken")
                // 工具下载不需要认证（CLI 内自带 Bearer）
                .excludePathPatterns("/api/tools/cli")
                // 健康检查
                .excludePathPatterns("/api/health/**")
                // 初始化向导
                .excludePathPatterns("/api/setup/**")
                // 活动流公开接口
                .excludePathPatterns("/api/feed/**");

        // 管理面路径限定：/api/admin/** 仅允许平台账号（拒绝外部 Agent 的 API Key）
        // 认证与授权分离：本拦截器不判角色；细粒度授权由 @SaCheckPermission 动作码承担
        registry.addInterceptor(new AdminOnlyInterceptor())
                .addPathPatterns("/api/admin/**");

        // Sa-Token 注解鉴权（@SaCheckPermission / @SaCheckRole，BASE-1.6 动作级权限码落地）
        // 仅对带鉴权注解的方法生效，不承担认证守门（守门由上面的 AuthInterceptor 完成）；
        // Agent / 公开白名单通道的方法不得加鉴权注解（§10 红线），因而天然不受影响。
        registry.addInterceptor(new SaInterceptor())
                .addPathPatterns("/api/**");
    }
}
