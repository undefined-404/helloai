package com.helloai.start.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 门铃响铃专用线程池（AgentHub 门铃响铃 PR-2）。
 *
 * <p>{@code DoorbellRinger} 在 {@code AFTER_COMMIT} 异步响铃时使用。与执行命令池隔离，
 * 避免门铃 SSE 写出（可能因第三方客户端慢消费而阻塞）拖累执行主链路的线程。</p>
 *
 * <p>响铃是尽力而为的旁路：队列满时直接丢弃（CallerRuns 会回压提交事务的线程，
 * 与"响铃不可拖累主链路"的初衷相悖），丢弃后由 Agent 的 pullTasks 轮询兜底。</p>
 */
@Configuration
public class DoorbellExecutorConfig {

    @Bean("doorbellExecutor")
    public Executor doorbellExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("doorbell-ring-");
        // 队列满即丢弃：响铃丢失不致命，绝不回压主链路（轮询兜底）
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
