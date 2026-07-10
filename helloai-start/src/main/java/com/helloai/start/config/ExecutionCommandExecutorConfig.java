package com.helloai.start.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 执行命令消费端专用线程池。
 *
 * <p>作为 single-JVM 内执行命令消费者的唯一运行边界，
 * 替代此前 {@code @Async(默认 SimpleAsyncTaskExecutor)} 的非池化线程创建。</p>
 */
@Configuration
public class ExecutionCommandExecutorConfig {

    @Bean("executionCommandExecutor")
    public Executor executionCommandExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("exec-cmd-");
        executor.initialize();
        return executor;
    }
}
