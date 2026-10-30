package com.helloai.core.planner.skill;

import com.helloai.common.config.SkillExecutionProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 对话内技能执行线程池（{@code @Async("skillExecutionExecutor")} 的目标）。
 *
 * <p><b>不复用 {@code chatStreamExecutor}</b>：那是 SSE 长连接的池（每条连接占用到流结束），
 * 技能执行是<b>长任务</b>（实测 377s）——混在一起，一条长技能就能把流式连接的线程全占掉，
 * 用户会看到「别的会话全卡住」。</p>
 *
 * <p><b>不复用 {@code chatAttachmentParseExecutor}</b>：那是 CPU/内存型短任务，
 * 与本池的「长任务 + 低并发」特征相反。</p>
 *
 * <p>拒绝策略 CallerRuns：极端打满时退回调用线程执行，任务不丢（宁可提交请求慢一点，
 * 也不要静默留下永远 PENDING 的执行记录）。</p>
 *
 * <p>返回类型刻意声明为 {@link Executor}：与 {@code helloai-start/config} 下的线程池一致，
 * 避免本池额外成为默认 {@code TaskExecutor} 候选（理由见 {@code ChatAttachmentAsyncConfig}）。</p>
 */
@Configuration
public class SkillExecutionAsyncConfig {

    @Bean(name = "skillExecutionExecutor")
    public Executor skillExecutionExecutor(SkillExecutionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, properties.getPoolSize()));
        executor.setMaxPoolSize(Math.max(1, properties.getPoolSize()));
        executor.setQueueCapacity(Math.max(1, properties.getQueueCapacity()));
        executor.setThreadNamePrefix("skill-exec-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
