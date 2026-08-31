package com.helloai.api.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Chat SSE 流式端点执行线程池。
 *
 * <p>SseEmitter 场景 Controller 必须快速返回 emitter，随后在独立线程完成
 * 「决策/搜索同步前置 + 主回复流式订阅」；线程池与 Tomcat 请求线程解耦，
 * 长占用的 LLM 流不阻塞 HTTP 线程回收。拒绝策略用 CallerRuns：流式连接数
 * 有限（单会话单连接），极端打满时退回调用线程执行，不静默丢任务。</p>
 */
@Slf4j
@Configuration
public class ChatStreamConfig {

    /** Chat 流式执行线程池（Controller 按名注入）。 */
    @Bean(name = "chatStreamExecutor")
    public ThreadPoolTaskExecutor chatStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("chat-stream-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        log.info("Chat 流式执行线程池初始化: core=4, max=8, queue=200");
        return executor;
    }
}