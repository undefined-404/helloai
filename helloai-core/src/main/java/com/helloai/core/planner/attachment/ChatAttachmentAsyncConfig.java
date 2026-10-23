package com.helloai.core.planner.attachment;

import com.helloai.common.config.ChatAttachmentProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 会话附件解析线程池（{@code @Async("chatAttachmentParseExecutor")} 的目标）。
 *
 * <p>为什么不复用 {@code chatStreamExecutor}：那是 SSE 长连接的线程池（每条连接占用到流结束），
 * 解析任务是 CPU/内存型短任务，混在一起会互相挤占——流式连接打满时解析拿不到线程，
 * 附件永远停在 PENDING，用户看到「解析中」不动。</p>
 *
 * <p>拒绝策略 CallerRuns：极端打满时退回调用线程执行，任务不丢（宁可上传请求慢一点，
 * 也不要静默留下永远 PENDING 的附件）。</p>
 *
 * <p><b>返回类型刻意声明为 {@link Executor} 而非 {@code ThreadPoolTaskExecutor}</b>：与
 * {@code helloai-start/config} 下 6 个线程池一致（{@code backupExecutor} /
 * {@code doorbellExecutor} / {@code executionCommandExecutor} / {@code plannerDecomposeExecutor} /
 * {@code reportReviewExecutor} / {@code reviewDualExecutor} 均声明为 {@code Executor}）。
 * 若声明为具体类，本池会额外成为 {@code TaskExecutor} 候选，与 {@code chatStreamExecutor}
 * 争「唯一默认执行器」——导致（a）按 {@code ThreadPoolTaskExecutor} 类型注入处歧义，
 * （b）字段无参的 {@code @Async} 方法在调用时找不到唯一执行器。
 * 本池只被 {@code @Async("chatAttachmentParseExecutor")} 按名使用，降到接口完全够用。</p>
 */
@Configuration
public class ChatAttachmentAsyncConfig {

    @Bean(name = "chatAttachmentParseExecutor")
    public Executor chatAttachmentParseExecutor(ChatAttachmentProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, properties.getParsePoolSize()));
        executor.setMaxPoolSize(Math.max(1, properties.getParsePoolSize()));
        executor.setQueueCapacity(Math.max(1, properties.getParseQueueCapacity()));
        executor.setThreadNamePrefix("chat-attach-parse-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
