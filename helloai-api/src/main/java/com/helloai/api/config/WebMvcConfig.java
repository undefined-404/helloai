package com.helloai.api.config;

import com.helloai.api.interceptor.AuthInterceptor;
import com.helloai.api.interceptor.RequestLogInterceptor;
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
    private final RequestLogMapper requestLogMapper;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 请求日志（所有 /api/**）
        registry.addInterceptor(new RequestLogInterceptor(requestLogMapper))
                .addPathPatterns("/api/**");

        // 认证拦截器
        registry.addInterceptor(new AuthInterceptor(authService))
                .addPathPatterns("/api/**")
                // 登录/登出不需要认证
                .excludePathPatterns("/api/auth/login")
                .excludePathPatterns("/api/auth/logout")
                .excludePathPatterns("/api/auth/me")
                // Agent 自助注册不需要认证
                .excludePathPatterns("/api/agents/register")
                .excludePathPatterns("/api/agents/register-with-token")
                // 工具下载不需要认证（CLI 内自带 Bearer）
                .excludePathPatterns("/api/tools/cli")
                // 健康检查
                .excludePathPatterns("/api/health/**")
                // 初始化向导
                .excludePathPatterns("/api/setup/**")
                // 活动流公开接口
                .excludePathPatterns("/api/feed/**");
    }
}
