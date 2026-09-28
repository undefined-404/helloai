package com.helloai.start.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 最终报告自动审查专用线程池（§12.2 审查异步化改造）。
 *
 * <p>最终报告审查（选 Reviewer + LLM 判定 + 可能的返工重写）是<b>编排内非关键路径</b>：
 * 报告生成成功即已交付（REVIEWING 展示中间态），审查质量闭环不应阻塞报告生成发布线程
 * （原实现同步执行，一次审查最长可达 LLM 调用超时窗口，会拖慢发布线程与后续事件）。
 * 审查任务提交到本池后发布线程立即返回。</p>
 *
 * <p>拒绝策略选 <b>AbortPolicy</b>（区别于 {@link ReviewDualExecutorConfig} 的 CallerRunsPolicy）：
 * 队列满（积累积压）时拒绝提交并在发布线程捕获 {@code RejectedExecutionException}，
 * 落 {@code task_final_report_review_skipped(reason=executor_saturated)} 并把报告收敛 DONE——
 * 审查是增值质量闭环，宁可本次跳过也不让慢审查占用全局线程；报告已交付，人工可手动重生成重审。</p>
 *
 * <p>独立小池（core 1 / max 2 / queue 50）防止并发的慢审查占满公共执行线程资源；
 * 与执行命令池、拆解池、门铃池、双审核验池相互隔离。</p>
 */
@Configuration
public class ReportReviewExecutorConfig {

    @Bean("reportReviewExecutor")
    public Executor reportReviewExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("report-review-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}