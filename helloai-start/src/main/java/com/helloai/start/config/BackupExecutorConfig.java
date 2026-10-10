package com.helloai.start.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 平台备份专用线程池（REF-2.3）。
 *
 * <p><b>为什么不复用既有三个池</b>（{@code executionCommandExecutor} /
 * {@code plannerDecomposeExecutor} / {@code doorbellExecutor}）：备份是**分钟级**操作
 * （{@code pg_dump} 全库），放进执行链的池会挤占命令消费的线程，
 * 把"备份慢"放大成"任务派发慢"。备份与业务链路的资源诉求正交，故独立成池。</p>
 *
 * <p><b>为什么单线程</b>：备份本来就被 Redisson 单飞锁串行化（全平台同时只允许一个），
 * 多线程没有收益，反而会多开 {@code pg_dump} 进程。队列留小容量兜底 —— 溢出即拒绝
 * （{@link ThreadPoolTaskExecutor} 默认 AbortPolicy），避免堆积成一批"排着队的备份"。</p>
 */
@Configuration
public class BackupExecutorConfig {

    @Bean("backupExecutor")
    public Executor backupExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("backup-");
        executor.initialize();
        return executor;
    }
}
