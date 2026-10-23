package com.helloai.start.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 应用级后台事件线程池（各事件监听器按名使用）。
 *
 * <p>存在的理由：容器里只要有一个 {@code TaskExecutor} bean，Spring 就把它当作<strong>所有</strong>
 * 无参 {@code @Async} 的默认执行器。项目里那个「唯一」曾经是 {@code chatStreamExecutor}
 * （对话流式专用，core=4），于是派发 / 评审 / 最终报告 / 质量回填 / 告警通知全部挤在 4 个对话线程上，
 * 线程名还显示成 {@code chat-stream-N}，排查时看不出真实来源。</p>
 *
 * <p>配合的约束：那 7 处 {@code @Async} 一律显式写成 {@code @Async("appEventExecutor")}，
 * 不再依赖「容器里恰好只剩一个」这种隐式约定——以后新增线程池时也不会悄悄改变它们的落点。</p>
 *
 * <p>拒绝策略 CallerRuns：这些事件都是流程推进的必要环节（派发 / 评审 / 报告落库 / 告警），
 * 丢弃会让子任务永远停在中间态；宁可回压提交线程慢一点，也不要静默丢事件。
 * 队列给足 1000，正常情况下不该走到拒绝分支。</p>
 *
 * <p>与 {@code chatStreamExecutor} 的分工：后者只跑对话流式订阅（用户能感知延迟），
 * 本池只跑后台事件（用户不直接等待），两者互不挤占。</p>
 */
@Slf4j
@Configuration
public class AppEventExecutorConfig {

    /** 后台事件执行池（{@code @Async} 按名引用）。 */
    @Bean("appEventExecutor")
    public Executor appEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("app-event-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        log.info("后台事件执行线程池初始化: core=4, max=16, queue=1000, prefix=app-event-");
        return executor;
    }
}
