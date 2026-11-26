package com.helloai;

import java.util.TimeZone;

import com.helloai.common.config.AgentProviderProperties;
import com.helloai.common.config.ContextWindowProperties;
import com.helloai.common.config.KbProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.helloai")
@EnableConfigurationProperties({AgentProviderProperties.class, KbProperties.class,
        ContextWindowProperties.class})
@MapperScan({
        "com.helloai.core.agent.mapper",
        "com.helloai.core.agent.session.mapper",
        "com.helloai.core.agent.quality.mapper",
        "com.helloai.core.task.mapper",
        "com.helloai.core.task.workflow.mapper",
        "com.helloai.core.review.mapper",
        "com.helloai.core.system.mapper",
        "com.helloai.core.planner.mapper",
        "com.helloai.core.planner.memory.mapper"
})
@EnableScheduling
@EnableAsync(proxyTargetClass = true)
public class HelloAIApplication {

    public static void main(String[] args) {
        // === §3.1 配套：禁用 CGLIB 类缓存 ===
        // 该开关是 JVM 系统属性（非 Spring 配置项），**只能**从这里或命令行给：
        //   - IDEA Run Configuration VM options：-Dcglib.cache.classes=true
        //   - 命令行：java -Dcglib.cache.classes=true -jar helloai-start.jar
        //   - Docker ENV：JAVA_TOOL_OPTIONS=-Dcglib.cache.classes=true
        // 写在 application.yml 里不生效（Spring 不把配置项反射成系统属性），
        // 2026-11-26 已删除原 yml 中的说明块，以免被当成"配置项存在"。
        // false（默认）：每次启动重新生成代理类，多花 ~2s，但异常退出（kill -9 / OOM / 断电）
        //   后不会残留损坏的 cglib 缓存。true：启动更快，但偶发 "Unable to load cache item"，
        //   需先清 %TEMP%\cglib* 才能启动。
        // 背景：spring-ai 1.x + spring-boot 3.4 + McpAuthFilterConfig CGLIB 增强。
        // 详细参见项目 memory "Spring Boot CGLIB 缓存污染诊断与修复"。
        String cglibCache = System.getProperty("cglib.cache.classes");
        if (cglibCache == null) {
            System.setProperty("cglib.cache.classes", "false");
        }
        SpringApplication.run(HelloAIApplication.class, args);
    }
}
