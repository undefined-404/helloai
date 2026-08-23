package com.helloai.start.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 双审核验专用线程池（§6.142 双审并行化改造）。
 *
 * <p>双审的两个不同模型 Reviewer 核验运行在 {@code reviewDualExecutor} 上并行执行，
 * 与执行命令池、拆解池、门铃池相互隔离：单侧核验最坏耗时可达 LLM 调用超时窗口
 * （由 {@code helloai.review.dual-review-timeout-seconds} 控制，默认 90s，
 * 严格收进核验互斥锁 TTL 120s 内——deadline 加锁后才起算，须留余量），
 * 独立小池防止慢核验占满公共线程资源。</p>
 *
 * <p>拒绝策略选 CallerRunsPolicy（而非 Abort/Discard）：双审核验是编排内联调用，
 * 队列满时由调用线程兜底直跑，保证核验不静默丢失——与"超时后 future 不取消、
 * 在途线程自然跑完"的语义一致（判定按不可判定走 incomplete，残留线程由池回收）。</p>
 */
@Configuration
public class ReviewDualExecutorConfig {

    @Bean("reviewDualExecutor")
    public Executor reviewDualExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("review-dual-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
