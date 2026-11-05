package com.helloai.core.agent.domain;

import com.helloai.core.agent.entity.Agent;
import org.springframework.beans.BeanUtils;

/**
 * 「把某次调用钉在指定模型上」的临时载体（2026-11-05）。
 *
 * <p><b>为什么需要它</b>：个人工作台让用户直接选<b>模型</b>（无角色），但平台的执行链是
 * <b>按 Agent 执行</b>的——{@code PlatformAgentExecutionService} 的所有入口都以
 * {@code Agent} 为模型/凭证的载体。让「选模型」真正生效有两条路：</p>
 *
 * <ul>
 *   <li>给 {@code AgentTask} 加 {@code modelType}，再逐个执行器（API_KEY_LLM / CLI / Browser /
 *       Runtime）判断覆盖——影响面横跨四类执行器与 Runtime 上下文装配，属「为一条会话链
 *       改造整条执行链」，与最小变更原则冲突；</li>
 *   <li><b>本类</b>：执行前用「原 Agent 的只读副本 + 覆盖后的 modelType」替换入参。
 *       凭证、能力、角色全部沿用原 Agent 的，唯一变化是模型。</li>
 * </ul>
 *
 * <p><b>为什么凭证不会错</b>：{@code AgentLlmCredentialResolver} / {@code ApiKeyAgentExecutor}
 * 都先按 {@code modelType} 的 provider 前缀解析供应商，再取<b>平台级</b>平台凭证
 * （内部 LLM Agent 不持有 AGENT 级 Key 副本）。因此覆盖后取到的仍是目标供应商的 Key，
 * 不存在「用 A 家密钥调 B 家模型」。</p>
 *
 * <p><b>铁律</b>：返回的是<b>游离副本</b>，只用于一次调用，<b>永远不得</b>传入任何
 * {@code save / updateById / insert}。它没有持久化意图，只借用执行的入参形态。</p>
 */
public final class AgentModelPin {

    private AgentModelPin() {
    }

    /**
     * 返回把 {@code modelType} 钉住后的 Agent 副本。
     *
     * <p>三种情况原样返回入参，不制造无谓副本：{@code agent == null}、
     * {@code modelType} 空、{@code modelType} 与原值相同——后两者保证「未指定模型」的
     * 全部既有链路行为零变化（包括对象身份，避免无意义的引用不等）。</p>
     *
     * @param agent     原 Agent（可为 null）
     * @param modelType 要钉住的 {@code provider:model}；空表示不钉住
     * @return 原对象或覆盖了 modelType 的游离副本
     */
    public static Agent pin(Agent agent, String modelType) {
        if (agent == null || modelType == null || modelType.isBlank()) {
            return agent;
        }
        String normalized = modelType.trim();
        if (normalized.equals(agent.getModelType())) {
            return agent;
        }
        Agent pinned = new Agent();
        BeanUtils.copyProperties(agent, pinned);
        pinned.setModelType(normalized);
        return pinned;
    }
}
