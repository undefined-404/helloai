package com.helloai;

import com.helloai.common.config.AgentProviderProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.helloai")
@EnableConfigurationProperties(AgentProviderProperties.class)
@MapperScan({
        "com.helloai.core.agent.mapper",
        "com.helloai.core.task.mapper",
        "com.helloai.core.system.mapper",
        "com.helloai.core.planner.mapper"
})
@EnableScheduling
@EnableAsync(proxyTargetClass = true)
public class HelloAIApplication {

    public static void main(String[] args) {
        // === §3.1 配套：禁用 CGLIB 类缓存 ===
        // 配置中心化在 application.yml 的 `cglib.cache-classes` 段（默认 false）；
        // 通过命令行 -Dcglib.cache.classes=true 可临时切回 true 验证是否还坏。
        // 背景：spring-ai 1.x + spring-boot 3.4 + McpAuthFilterConfig CGLIB 增强
        // 在异常退出后偶发导致下次启动失败（cglib cache item Unable to load）。
        // 详细参见项目 memory "Spring Boot CGLIB 缓存污染诊断与修复"。
        String cglibCache = System.getProperty("cglib.cache.classes");
        if (cglibCache == null) {
            System.setProperty("cglib.cache.classes", "false");
        }
        SpringApplication.run(HelloAIApplication.class, args);
    }
}
