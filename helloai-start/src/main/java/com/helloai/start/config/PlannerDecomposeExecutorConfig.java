package com.helloai.start.config;

import com.helloai.common.config.PlannerDecomposeProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Planner 任务拆解专用线程池（拆解异步化改造）。
 *
 * <p>拆解的 LLM 调用运行在 {@code plannerDecomposeExecutor} 上，与执行命令池、
 * 门铃池相互隔离：拆解单次最坏耗时约 4 分钟（信号量排队 60s + 连接 5s + 读取 180s），
 * 独立小池可防止慢拆解占满公共线程资源，也避免拆解阻塞执行主链路。</p>
 *
 * <p>拒绝策略选 AbortPolicy（而非 CallerRuns/Discard）：拆解命令不可静默丢弃，
 * 队列满时抛 {@code TaskRejectedException}，由 {@code PlannerAnalysisServiceImpl.decompose}
 * 捕获后回退任务 PENDING 并向前端返回"排队已满，请稍后重试"的友好提示（CODE_STYLE §15.4）。</p>
 *
 * <p>线程池参数外置在 application.yml 的 {@code helloai.planner.decompose} 配置段，
 * 由 {@link PlannerDecomposeProperties} 承载。</p>
 */
@Configuration
@RequiredArgsConstructor
public class PlannerDecomposeExecutorConfig {

    private final PlannerDecomposeProperties plannerDecomposeProperties;

    @Bean("plannerDecomposeExecutor")
    public Executor plannerDecomposeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(plannerDecomposeProperties.getCorePoolSize());
        executor.setMaxPoolSize(plannerDecomposeProperties.getMaxPoolSize());
        executor.setQueueCapacity(plannerDecomposeProperties.getQueueCapacity());
        executor.setThreadNamePrefix("planner-decompose-");
        // 队列满即拒绝：decompose 捕获后回退 PENDING 并提示用户稍后重试，绝不静默丢任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
