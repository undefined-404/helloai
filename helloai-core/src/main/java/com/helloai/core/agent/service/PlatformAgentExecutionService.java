package com.helloai.core.agent.service;

import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import reactor.core.publisher.Flux;

/**
 * 平台内 Agent 执行入口。
 *
 * <p>统一 service 入口，避免把执行编排逻辑散落到 Controller / MQ consumer 中。</p>
 */
public interface PlatformAgentExecutionService {

    /**
     * 按 agentId 执行一次 Agent 任务（异步入口，适用于已确认在线的 Agent）。
     */
    AgentResult execute(Long agentId, AgentTask task);

    /**
     * 按 Agent 实体执行一次 Agent 任务（异步入口）。
     */
    AgentResult execute(Agent agent, AgentTask task);

    /**
     * 同步执行一次 Agent 任务：内部完成"心跳保活 + 执行器路由 + LLM 调用"全链路。
     */
    AgentResult executeSync(Agent agent, AgentTask task);

    /**
     * 同步执行一次 Agent 任务（按 agentId 解析 Agent）。
     */
    AgentResult executeSync(Long agentId, AgentTask task);

    /**
     * 流式执行一次 Agent 任务（token 增量 Flux，订阅时发起调用）：
     * 内部完成"心跳保活 + 执行器路由 + 流式 LLM 调用"全链路，与同步
     * {@link #executeSync(Agent, AgentTask)} 仅传输形态不同。
     *
     * <p>仅支持实现了流式通道的执行器（当前为 API_KEY_LLM 内部通道）；
     * 外部 Agent / 未实现通道的执行器在订阅时抛 BizException，由调用方回退同步语义。</p>
     */
    Flux<String> executeStream(Agent agent, AgentTask task);
}
