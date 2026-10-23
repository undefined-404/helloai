package com.helloai.api.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Chat SSE 流式端点执行线程池。
 *
 * <p>SseEmitter 场景 Controller 必须快速返回 emitter，随后在独立线程完成
 * 「决策/搜索同步前置 + 主回复流式订阅」；线程池与 Tomcat 请求线程解耦，
 * 长占用的 LLM 流不阻塞 HTTP 线程回收。拒绝策略用 CallerRuns：流式连接数
 * 有限（单会话单连接），极端打满时退回调用线程执行，不静默丢任务。</p>
 *
 * <p><b>返回类型声明为 {@link Executor} 而非 {@code ThreadPoolTaskExecutor}</b>（与
 * {@code helloai-start/config} 下 7 个池一致，见 {@code CODE_STYLE} §7.4.2）：写成具体类会让
 * 本池成为容器内唯一的 {@code TaskExecutor} 候选，被 Spring 当成**所有无参 {@code @Async}**
 * 的默认执行器——后台派发 / 评审 / 报告生成会静默挤占这 4 个对话线程，且线程名显示成
 * {@code chat-stream-N}，排查时看不出真实来源。降到接口后这些池子彼此都不参与默认执行器争夺，
 * 后台事件走按名指定的 {@code appEventExecutor}。</p>
 */
@Slf4j
@Configuration
public class ChatStreamConfig {

    /** Chat 流式执行线程池（Controller 按名注入）。 */
    @Bean(name = "chatStreamExecutor")
    public Executor chatStreamExecutor() {
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