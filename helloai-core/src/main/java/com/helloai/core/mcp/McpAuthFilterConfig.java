package com.helloai.core.mcp;

import com.helloai.core.system.service.AuthService;
import lombok.RequiredArgsConstructor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.beans.factory.ObjectProvider;

/**
 * MCP Server 鉴权 Filter 注册配置（v2.4 §3.1 / §9 路线 C M4 鉴权改造）。
 *
 * <p><b>为什么手写 {@link FilterRegistrationBean}？</b>
 * 如果用 {@code @Component} 让 Spring Boot 自动注册，filter 顺序无法控制。
 * 显式注册 + {@code setOrder(HIGHEST_PRECEDENCE + 10)} 让 MCP 鉴权早于
 * {@code RequestLogInterceptor}（避免无效请求写日志 DB）和业务 Interceptor。</p>
 *
 * <p><b>注意</b>：{@link McpAuthFilter} 类上<b>不要</b>加 {@code @Component}，
 * 避免 Spring Boot 自动注册产生 duplicate bean 警告。</p>
 *
 * @author helloai
 * @see McpAuthFilter
 */
@Configuration
@RequiredArgsConstructor
public class McpAuthFilterConfig {

    private final AuthService authService;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    @Bean
    public FilterRegistrationBean<McpAuthFilter> mcpAuthFilterRegistration() {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new);
        McpAuthFilter filter = new McpAuthFilter(authService, meterRegistry);
        FilterRegistrationBean<McpAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/mcp/*");
        registration.setName("mcpAuthFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
