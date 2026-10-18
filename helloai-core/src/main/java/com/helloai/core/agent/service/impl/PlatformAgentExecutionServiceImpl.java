package com.helloai.core.agent.service.impl;

import com.helloai.core.agent.service.HeartbeatService;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.executor.AgentExecutor;
import com.helloai.core.agent.executor.AgentExecutorRouter;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.runtime.BusinessSurfaceLoopRunner;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import com.helloai.core.agent.service.AgentService;

/**
 * 平台内 Agent 执行入口。
 *
 * <p>先作为统一 service 入口，避免未来把执行编排逻辑散落到 Controller / MQ consumer 中。</p>
 *
 * <p><b>REF-8.5 覆盖面收敛（2026-10-16）</b>：本类是全部业务 LLM 面的<b>唯一咽喉</b>
 * （4 个已裁定进循环面全经 {@link #executeSync} 到 {@link #execute(Agent, AgentTask)}）。
 * 故循环化插点落在此处一处，而非散改 4 个调用点：白名单内的 {@code scene} 交由
 * {@link BusinessSurfaceLoopRunner} 以工具循环执行；不命中 / 循环不可用时静默走原有
 * 单次链路（零行为变化）。</p>
 */
@Service
@RequiredArgsConstructor
public class PlatformAgentExecutionServiceImpl implements PlatformAgentExecutionService {

    private final AgentService agentService;
    private final AgentExecutorRouter agentExecutorRouter;
    private final HeartbeatService heartbeatService;
    private final AgentExecutionProperties executionProperties;
    private final AgentSkillSpecService agentSkillSpecService;
    /**
     * 业务面循环入口（REF-8.5）：非命中白名单时本身不做任何事。
     * 注入失败会让应用启动失败——刻意如此（覆盖面是已裁定的能力，不允许静默缺失）。
     */
    private final BusinessSurfaceLoopRunner businessSurfaceLoopRunner;

    /**
     * 按 agentId 路由并执行。
     */
    public AgentResult execute(Long agentId, AgentTask task) {
        Agent agent = agentService.getById(agentId);
        if (agent == null) {
            throw new BizException("Agent 不存在: " + agentId);
        }
        return execute(agent, task);
    }

    /**
     * 按 Agent 实体路由并执行。
     */
    public AgentResult execute(Agent agent, AgentTask task) {
        AgentExecutor executor = agentExecutorRouter.route(agent);
        if (!executor.checkCapability(agent, task.getRequiredCapabilities())) {
            throw new BizException("Agent 能力不足: agentId=" + agent.getId()
                    + ", executor=" + executor.getName());
        }
        // 契约层统一技能注入：skills 非空时 resolve 规范段拼入 systemPrompt（执行链不填 → 零变化）
        task = applySkillInjection(task);
        heartbeatService.active(agent.getId());
        // REF-8.5：白名单内的业务面改走工具循环；未命中 / 循环不可用返回 null ⇒ 回落单次
        if (businessSurfaceLoopRunner.supports(agent, task)) {
            AgentResult loopResult = businessSurfaceLoopRunner.runInLoop(agent, task);
            if (loopResult != null) {
                return loopResult;
            }
        }
        return executor.execute(agent, task);
    }

    /**
     * 同步执行，便于最小验证入口和测试使用。
     */
    public AgentResult executeSync(Agent agent, AgentTask task) {
        return execute(agent, task);
    }

    /**
     * 同步执行，按 agentId 路由。
     */
    public AgentResult executeSync(Long agentId, AgentTask task) {
        return execute(agentId, task);
    }

    /**
     * 流式执行：路由 → 能力校验 → 心跳保活 → 执行器流式通道，与同步
     * {@link #executeSync(Agent, AgentTask)} 同构；全部包进 Flux.defer 保证惰性
     * （订阅时才路由/打卡，供上层在业务线程池里订阅）。
     *
     * <p><b>REF-8.5 刻意不在此插点</b>：流式面的消费语义是「token 逐个转发前端（打字机）」，
     * 循环的中间轮次（工具调用 / 观察）没有对应的流式承载，进循环须先改造流式链路——
     * 覆盖面清单（plan §10.6）已把两个流式面（澄清 `requirement_chat` / `requirement_clarify`）
     * 明确裁定为「留单次」。此处<b>不</b>接循环是裁定结果，不是遗漏。</p>
     */
    @Override
    public Flux<com.helloai.core.agent.chat.LlmStreamChunk> executeStream(Agent agent, AgentTask task) {
        return Flux.defer(() -> {
            AgentExecutor executor = agentExecutorRouter.route(agent);
            if (!executor.checkCapability(agent, task.getRequiredCapabilities())) {
                throw new BizException("Agent 能力不足: agentId=" + agent.getId()
                        + ", executor=" + executor.getName());
            }
            // 契约层统一技能注入：与同步 execute 同一点位（checkCapability 之后、执行器之前）
            AgentTask injected = applySkillInjection(task);
            heartbeatService.active(agent.getId());
            return executor.executeStream(agent, injected);
        });
    }

    /**
     * 流式执行（按 agentId 路由）：与 {@link #executeStream(Agent, AgentTask)} 同构，
     * 仅多一步按 id 解析 Agent。解析包进 {@code Flux.defer} 保证惰性（订阅时才查库/路由/打卡），
     * Agent 不存在时在订阅时抛 BizException（与 {@link #execute(Long, AgentTask)} 的守卫一致）。
     */
    @Override
    public Flux<com.helloai.core.agent.chat.LlmStreamChunk> executeStream(Long agentId, AgentTask task) {
        return Flux.defer(() -> {
            Agent agent = agentService.getById(agentId);
            if (agent == null) {
                throw new BizException("Agent 不存在: " + agentId);
            }
            return executeStream(agent, task);
        });
    }

    /**
     * 契约层技能规范注入：{@code AgentTask.skills} 非空时把 resolve 出的「平台技能规范（执行速览）」
     * 段拼入 systemPrompt。skills 为空 / 无命中 / 段为空时原样返回（全部既有链路行为零变化）。
     */
    private AgentTask applySkillInjection(AgentTask task) {
        if (task.getSkills() == null || task.getSkills().isEmpty()) {
            return task;
        }
        AgentSkillSpecService.ResolvedSpec resolved = agentSkillSpecService.resolve(task.getSkills());
        if (resolved == null || resolved.section() == null || resolved.section().isBlank()) {
            return task;
        }
        String base = task.getSystemPrompt() == null ? "" : task.getSystemPrompt();
        String section = "## 平台技能规范（执行速览）\n" + resolved.section();
        return task.withSystemPrompt(base.isBlank() ? section : base + "\n\n" + section);
    }
}
