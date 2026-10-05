package com.helloai.core.agent.service;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentEventType;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.AgentLlmCredentialResolver;
import com.helloai.core.agent.chat.AgentProviderResolver;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.event.AgentEventContextResolver;
import com.helloai.core.agent.event.AgentEventQueryService;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.event.AgentEventTraceItem;
import com.helloai.core.agent.quality.service.AgentQualityProfileService;
import com.helloai.core.agent.runtime.AgentContext;
import com.helloai.core.agent.runtime.AgentExecutionResult;
import com.helloai.core.agent.runtime.ExecutionEnvironment;
import com.helloai.core.agent.runtime.loop.LoopCheckpointListener;
import com.helloai.core.agent.session.service.AgentSessionService;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.agent.tool.ToolDefinition;
import com.helloai.core.agent.tool.ToolRegistry;
import com.helloai.core.shared.util.SubTaskOutputExtractor;
import com.helloai.core.shared.util.TextTruncator;
import com.helloai.core.shared.util.UpstreamAttachmentRenderer;
import com.helloai.core.agent.port.AttachmentPort;
import com.helloai.core.agent.port.AttachmentRef;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.port.TaskRunningSpecPort;
import com.helloai.core.agent.port.TaskTimelinePort;
import com.helloai.core.agent.port.UncertaintySnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Agent Runtime 真身上下文装配器（G-002 单轨收敛，2026-09-30）。
 *
 * <p><b>职责</b>：把执行命令 + 子任务 + Agent 装配为 {@link AgentContext}（Runtime 真身
 * {@code RuntimeTurnExecutor} 的完整输入），承接旧链 {@code SubTaskExecutionServiceImpl.executeOnce}
 * 的装配职责（G-002 迁移后旧链入口退役，装配逻辑平移至此，行为等价）：</p>
 * <ul>
 *     <li>Prompt 装配：Task Running Spec 全局段 + 技能规范段 + 执行者历史画像段 + 依赖产出参考段
 *         + 重派恢复上下文（B3 Resume 起含事件流「已完成的事实」）+ 返工修正指引 + 产出回填要求
 *         （EXECUTION_RECORD / manifest 多文件协议）；</li>
 *     <li>底层模型：按 provider + API Key 经 {@link AgentChatClientService#buildChatModel} 构建
 *         （mock 模式 MockChatModel；真实模式 Vault/Agent 级凭据解析，requireVault 校验同旧口径）；</li>
 *     <li>执行会话：LLM 调用前 {@code AgentSessionService.start}（step=2，中断点快照，best-effort），
 *         并装配循环进度回调（P0-C checkpoint：AgentLoop 每轮 merge snapshot.loop）；</li>
 *     <li>对话流落库：实际送给 LLM 的 prompt 全量入 conversation_message（user 视角，best-effort）；</li>
 *     <li>可观测 timeline：spec 装配事实 / llm 调用起止（与旧链同键同语义）。</li>
 * </ul>
 *
 * <p><b>边界</b>：Turn 事件骨架（AGENT_STARTED / SKILL_RESOLVED / TOOL_RESOLVED /
 * ENVIRONMENT_RESOLVED / CONTEXT_BUILT / TOOL_CALL）由 {@code RuntimeTurnExecutor} 在真身内
 * 自行记录（与旧链同 payload 语义），本类不再记录——避免事件双写。本类不做状态推进
 * （{@code SubTaskExecutionService.startIfNeeded}）与结果回写（{@link ExecutionResultHandler}），
 * 两者是消费侧（{@code LocalExecutionCommandConsumer}）的编排职责。</p>
 *
 * <p><b>跨域访问（2026-10-01 W11）</b>：本类曾直接 import {@code task.entity.SubTask} /
 * {@code task.entity.Attachment} / {@code task.entity.Uncertainty} / {@code task.service.*} /
 * {@code task.spec.ExecutionRecord}，构成 CODE_STYLE §6 反向依赖（{@code agent → task}）。
 * 现一律改经 {@code agent.port} 端口：子任务走 {@link SubTaskQueryPort}（{@link SubTaskSnapshot}
 * 快照，含 `dependsOn` 与 `uncertainties` 投影）、附件走 {@link AttachmentPort}
 * （{@link AttachmentRef}，其 `contentLoadable` 与不确定性的 `assumption` 均由 <b>task 侧映射器</b>
 * 派生后透传，本域不复制 task 常量）、执行记录走 {@link TaskRunningSpecPort}。
 * 端口归属判据见 §7.2：消费方 agent <b>低于</b>提供方 task ⇒ 契约落消费方、适配器落提供方。</p>
 *
 * <p><b>容错纪律</b>：与旧链一致的 best-effort 降级哲学——会话 / 对话流 / 画像 / 依赖段 /
 * 恢复上下文任一部分失败均降级不阻断执行；唯一 fail-close 点是真实模式下 API Key 缺失
 * （Runtime 循环必须可解析凭据，契约化失败）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRuntimeContextAssembler {

    /**
     * 单条前置产出注入的字符上限（迁移自 SubTaskExecutionServiceImpl，语义不变）。
     *
     * <p>2026-10-03 上调：4000 → 64000。所有 LLM 走官方 api-key（DeepSeek 64K~1M 上下文），
     * 4000 对 64K 上下文是自我阉割——硬切导致下游 executor 看不到前置关键产出、诱发幻觉，
     * 属纯沉默成本。与核验侧（{@link AttachmentContentPolicy#ATTACHMENT_CONTENT_PER_FILE_LIMIT}）
     * 及报告侧首档（64000）口径对齐，统一为「正常产出全量注入 + 极端超长兜底截断」。</p>
     */
    private static final int DEP_CONTENT_MAX_CHARS = 64000;

    private final AgentExecutionProperties executionProperties;
    private final AgentChatClientService agentChatClientService;
    private final AgentLlmCredentialResolver agentLlmCredentialResolver;
    private final TaskTimelinePort taskTimelinePort;
    /** 执行记录端口（原 {@code TaskRunningSpecService}；W11 端口化）。 */
    private final TaskRunningSpecPort taskRunningSpecPort;
    private final AgentSkillSpecService agentSkillSpecService;
    private final AgentQualityProfileService agentQualityProfileService;
    private final AgentSessionService agentSessionService;
    private final ConversationService conversationService;
    /** 附件端口（原 {@code AttachmentService}；W11 端口化，读快照 {@link AttachmentRef}）。 */
    private final AttachmentPort attachmentPort;
    /** 子任务只读端口（原 {@code SubTaskService}；W11 端口化，读快照 {@link SubTaskSnapshot}）。 */
    private final SubTaskQueryPort subTaskQueryPort;
    private final ToolRegistry toolRegistry;
    private final AgentEventRecorder agentEventRecorder;
    /**
     * 事件流读侧契约（B3 Resume·Prompt 级结构化续接）：重派接续时从 {@code agent_event}
     * 提取「已完成 Step 事实」（Step 槽位 + 已成功工具调用及结果摘要），供接续者避免重复。
     * 与 {@link #agentEventRecorder}（写侧）同域（{@code core.agent.event}），依赖顺向。
     */
    private final AgentEventQueryService agentEventQueryService;

    /**
     * 装配 Runtime 真身执行上下文（消费侧在 startIfNeeded 之后、执行之前调用）。
     *
     * <p>内部完成：Prompt 装配 → ChatModel 构建 → 执行会话开始（step=2）→ 对话流落库 →
     * spec 装配 timeline + llm_call_start。事件骨架不在此记录（真身内发，防双写）。</p>
     *
     * @throws BizException 子任务状态不可执行 / 真实模式 API Key 不可解析（契约化失败，由消费侧回写）
     */
    public AgentContext assemble(ExecutionCommand command, SubTaskSnapshot subTask, Agent agent,
                                 List<String> tools, ExecutionEnvironment environment) {
        Long subTaskId = subTask.id();
        if (subTask.status() == com.helloai.common.constant.SubTaskStatus.DONE
                || subTask.status() == com.helloai.common.constant.SubTaskStatus.CANCELLED) {
            throw new BizException("子任务不可执行: status=" + subTask.status());
        }

        int runTurn = AgentEventContextResolver.resolveTurn(subTask.reworkCount(), subTask.attemptTotal());
        String runId = AgentEventContextResolver.resolveRunId(subTask.taskId());
        List<String> requiredSkills = command.getRequiredSkills() != null
                ? command.getRequiredSkills() : Collections.emptyList();

        // 1) Prompt 装配：全局段（Task Running Spec）+ 插件规范段 + 历史表现段 + 依赖段 + 恢复上下文
        String promptSection = taskRunningSpecPort.buildExecutorPromptSection(subTask.taskId());
        AgentSkillSpecService.ResolvedSpec resolved = agentSkillSpecService.resolve(requiredSkills);
        if (resolved == null) {
            resolved = new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), "");
        }
        promptSection = mergeSpecSections(promptSection, resolved.section());
        String historySection = renderHistorySectionSafely(agent);
        promptSection = mergeSpecSections(promptSection, historySection);
        DependencySectionResult dependencySection = buildDependencySection(subTask);
        AgentSessionService.InterruptedSession recovery = findRecoverySafely(subTaskId);
        // B3 Resume：仅重派接续场景（recovery 命中）才查事件流，无中断时不产生额外查询
        List<AgentEventTraceItem> completedTrace = recovery != null
                ? findTraceSafely(subTaskId, recovery.turn()) : List.of();
        String userPrompt = buildUserPrompt(subTask, promptSection, dependencySection.section, recovery,
                completedTrace);

        // 2) 启用工具并集（命令 tools ∪ 命中技能 requiredTools，与旧链同语义）
        List<String> enabledTools = mergeTools(tools, resolved.requiredTools());
        List<ToolDefinition> resolvedTools = toolRegistry.resolve(enabledTools);
        if (resolvedTools == null) {
            resolvedTools = List.of();
        }

        // 3) 底层模型（mock 返回 MockChatModel；真实模式显式 provider + API Key，缺失 fail-close）
        ChatModel chatModel = buildChatModel(agent);

        // 4) 执行会话开始（step=2=上下文装配完成/LLM 前；快照承载恢复上下文，best-effort）
        agentSessionService.start(subTask.taskId(), subTaskId, agent.getId(), runTurn, 2,
                safeMap("agentId", agent.getId(),
                        "skills", requiredSkills,
                        "tools", enabledTools,
                        "environment", environment != null ? environment.name() : null,
                        "depCount", dependencySection.depCount,
                        "loadedCount", dependencySection.loadedCount,
                        "truncatedCount", dependencySection.truncatedCount));

        // 4.1) 循环进度落库回调（P0-C checkpoint：AgentLoop 每轮 iteration 边界 merge
        // snapshot.loop；服务侧 best-effort，lambda 只做透传）
        LoopCheckpointListener loopCheckpointListener = checkpoint ->
                agentSessionService.saveLoopCheckpoint(subTaskId, runTurn, checkpoint);

        // 5) spec 装配可观测（与旧链同键同事件名）
        taskTimelinePort.recordEvent(subTask.taskId(), subTaskId, "sub_task_spec_context_loaded",
                AgentRole.EXECUTOR, agent.getId(),
                safeMap("agentId", agent.getId(),
                        "depCount", dependencySection.depCount,
                        "loadedCount", dependencySection.loadedCount,
                        "truncatedCount", dependencySection.truncatedCount,
                        "degraded", dependencySection.degraded,
                        "pluginSpec", resolved.section() != null && !resolved.section().isBlank(),
                        "historySummary", historySection != null && !historySection.isBlank(),
                        "recoveryInjected", recovery != null));
        taskTimelinePort.recordEvent(subTask.taskId(), subTaskId, "sub_task_llm_call_start",
                AgentRole.EXECUTOR, agent.getId(),
                Map.of("agentId", agent.getId(), "agentName", agent.getName()));

        // 6) 对话流 user 视角落库（实际送给 LLM 的 prompt 全量；best-effort）
        try {
            conversationService.addMessage(subTaskId, agent.getId(), "user", "agent",
                    userPrompt, "sub_task_execute_user_prompt");
        } catch (Exception e) {
            log.warn("执行请求对话流写入失败（不阻断主链路）: subTaskId={}, err={}", subTaskId, e.getMessage());
        }

        return AgentContext.builder()
                .runId(runId)
                .taskId(subTask.taskId())
                .subTaskId(subTaskId)
                .turn(runTurn)
                .step(0)
                .agentId(command.getAgentId())
                .skills(requiredSkills)
                .tools(enabledTools)
                .environment(environment)
                .accessType(command.getAccessType())
                .systemPrompt("")
                .userPrompt(userPrompt)
                .chatModel(chatModel)
                .eventRecorder(agentEventRecorder)
                .loopCheckpointListener(loopCheckpointListener)
                .build();
    }

    /**
     * Turn 执行完成后的会话推进（LLM 调用完成，step=4=结果回写前；best-effort）。
     * 由消费侧在 {@code AgentRuntime.execute} 返回后调用，与旧链 executeOnce 尾部行为等价。
     */
    public void afterTurn(SubTaskSnapshot subTask, Agent agent, int turn, AgentExecutionResult result) {
        try {
            agentSessionService.advance(subTask.id(), agent.getId(), turn, 4);
        } catch (Exception e) {
            log.debug("执行会话推进失败（best-effort 降级）: subTaskId={}, err={}", subTask.id(), e.getMessage());
        }
        taskTimelinePort.recordEvent(subTask.taskId(), subTask.id(), "sub_task_llm_call_end",
                AgentRole.EXECUTOR, agent.getId(),
                safeMap("agentId", agent.getId(),
                        "success", result != null && result.getStatus() == com.helloai.common.constant.ExecutionStatus.SUCCESS,
                        "finishReason", result != null ? result.getFinishReason() : null,
                        "tokens", result != null ? result.getTokenUsage() : null));
    }

    /** mock 模式返回 null；真实模式解析 Vault/Agent 级 API Key（requireVault 校验同旧口径）。 */
    private ChatModel buildChatModel(Agent agent) {
        String provider = AgentProviderResolver.resolveProvider(agent, executionProperties.getProvider());
        String apiKey = null;
        if (!executionProperties.isMockMode()) {
            apiKey = agentLlmCredentialResolver.resolveApiKey(agent);
            if (executionProperties.isRequireVault() && (apiKey == null || apiKey.isBlank())) {
                throw new BizException("未配置可用的平台级或 Agent 级 API Key: agentId=" + agent.getId());
            }
        }
        return agentChatClientService.buildChatModel(agent, provider, apiKey);
    }

    // #region Prompt 装配（迁移自 SubTaskExecutionServiceImpl，行为等价）

    /** 合并 Task Running Spec 全局段与平台技能规范段（空段原样返回另一段，同旧语义）。 */
    private String mergeSpecSections(String specSection, String pluginSection) {
        boolean pluginBlank = pluginSection == null || pluginSection.isBlank();
        boolean specBlank = specSection == null || specSection.isBlank();
        if (pluginBlank) {
            return specSection;
        }
        if (specBlank) {
            return pluginSection;
        }
        return specSection + "\n\n" + pluginSection;
    }

    /** 历史表现段安全渲染：画像查询/渲染异常降级为空串，绝不让副链路拖死执行主链路。 */
    private String renderHistorySectionSafely(Agent agent) {
        try {
            return agentQualityProfileService.renderHistorySection(agent.getId());
        } catch (Exception e) {
            log.debug("历史表现段渲染失败（best-effort 降级，零注入）: agentId={}, err={}",
                    agent.getId(), e.getMessage());
            return "";
        }
    }

    /** 恢复上下文安全查询：会话查询异常一律降级为 null（零注入零阻断）。 */
    private AgentSessionService.InterruptedSession findRecoverySafely(Long subTaskId) {
        try {
            return agentSessionService.findLatestInterrupted(subTaskId);
        } catch (Exception e) {
            log.debug("恢复上下文查询失败（best-effort 降级，零注入）: subTaskId={}, err={}",
                    subTaskId, e.getMessage());
            return null;
        }
    }

    /**
     * 事件流轨迹安全查询（B3 Resume）：优先取中断 Turn 的轨迹；该 Turn 无事件时退回全量轨迹
     * （兼容事件埋点降级/旧路径）；查询异常一律降级为空列表（零注入零阻断）。
     */
    private List<AgentEventTraceItem> findTraceSafely(Long subTaskId, int turn) {
        try {
            List<AgentEventTraceItem> trace = agentEventQueryService.traceBySubTaskId(subTaskId);
            if (trace == null || trace.isEmpty()) {
                return List.of();
            }
            List<AgentEventTraceItem> sameTurn = trace.stream()
                    .filter(item -> item.getTurn() != null && item.getTurn() == turn)
                    .toList();
            return sameTurn.isEmpty() ? trace : sameTurn;
        } catch (Exception e) {
            log.debug("事件流轨迹查询失败（best-effort 降级，零注入）: subTaskId={}, err={}",
                    subTaskId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 组装执行 Prompt：任务全局上下文 + 依赖产出参考（直接前置）+ 当前子任务四要素 +
     * 执行恢复上下文（重派接续，仅命中时注入；B3 Resume 起附带事件流已完成事实）+
     * 返工修正指引 + 回填要求。
     */
    private String buildUserPrompt(SubTaskSnapshot subTask, String runningSpecSection, String dependencySection,
                                   AgentSessionService.InterruptedSession recovery,
                                   List<AgentEventTraceItem> completedTrace) {
        StringBuilder sb = new StringBuilder();

        // 任务全局上下文（Task Running Spec）
        if (runningSpecSection != null && !runningSpecSection.isBlank()) {
            sb.append(runningSpecSection);
            sb.append("\n---\n\n");
        }

        // 依赖产出参考（直接前置）：有依赖才注入，无依赖零注入
        if (dependencySection != null && !dependencySection.isBlank()) {
            sb.append(dependencySection);
            sb.append("\n---\n\n");
        }

        // 当前子任务四要素
        sb.append("## 当前任务\n");
        sb.append("任务标题: ").append(subTask.title()).append("\n");
        if (subTask.content() != null && !subTask.content().isBlank()) {
            sb.append("任务描述: ").append(subTask.content()).append("\n");
        }
        if (subTask.deliverable() != null && !subTask.deliverable().isBlank()) {
            sb.append("交付物要求: ").append(subTask.deliverable()).append("\n");
        }
        if (subTask.acceptance() != null && !subTask.acceptance().isBlank()) {
            sb.append("验收标准: ").append(subTask.acceptance()).append("\n");
        }

        // G-011 D6 三段增量（空值零注入）：执行约束补偿 + 不确定性分级申报 + 事实回源声明
        if (subTask.constraints() != null && !subTask.constraints().isBlank()) {
            sb.append("执行约束（不许改的事）: ").append(subTask.constraints()).append("\n");
        }
        if (subTask.uncertainties() != null && !subTask.uncertainties().isEmpty()) {
            sb.append("不确定性申报:\n");
            for (UncertaintySnapshot u : subTask.uncertainties()) {
                if (u.note() == null || u.note().isBlank()) {
                    continue;
                }
                // assumption 由 task 侧映射器判定（常量单源留 task 域，避免 agent 侧复制或硬编码 kind）
                String suffix = u.assumption()
                        ? "（可自行验证，推翻即上报）"
                        : "（须先验证再动手，无法验证则 BLOCKED 上报）";
                sb.append("- [").append(u.kind()).append("] ").append(u.note())
                        .append(suffix).append("\n");
            }
        }
        sb.append("验收事实回源：生产系统当前行为以代码与配置为准；业务意图以本任务描述与已确认需求包为准；"
                + "历史兼容行为不得在未声明的情况下「优化」移除。\n");

        // 执行恢复上下文（N-007 B1）：重派接续时注入上一次被中断尝试的摘要
        // + B3 Resume：附带事件流「已完成的事实」，明确接续者无需重复的工作
        appendRecoveryContext(sb, recovery, completedTrace);

        // 返工上下文：上次提交被审核驳回，需参考驳回意见修正
        appendReworkContext(sb, subTask);

        // 回填要求（EXECUTION_RECORD 协议）
        sb.append("\n---\n\n");
        sb.append("## 产出回填要求\n");
        sb.append("请在完成交付物输出后，在输出的最后附上以下结构化回填块：\n\n");
        sb.append("```\n");
        sb.append("## EXECUTION_RECORD\n");
        sb.append("SUMMARY: <1-2句核心产出描述>\n");
        sb.append("KEY_DECISIONS:\n");
        sb.append("- <关键决策1>\n");
        sb.append("DOWNSTREAM_NOTES:\n");
        sb.append("- <下游子任务需要注意的事项>\n");
        sb.append("DELIVERABLES:\n");
        sb.append("- <产出文件路径>\n");
        sb.append("```\n");

        // 可选：LLM manifest 多文件产出协议（方案3）——命中时多文件物化，未命中降级纯文本单 .md，零影响
        sb.append("\n你也可以选择用如下 JSON 结构返回多文件产出（放在 ```json 代码块中，位于 EXECUTION_RECORD 块之前）：\n\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"summary\": \"本次产出的简要说明\",\n");
        sb.append("  \"files\": [\n");
        sb.append("    { \"name\": \"README.md\", \"type\": \"text/markdown\", \"content\": \"...\" },\n");
        sb.append("    { \"name\": \"main.py\", \"type\": \"text/x-python\", \"content\": \"...\" }\n");
        sb.append("  ]\n");
        sb.append("}\n");
        sb.append("```\n");
        sb.append("若无需拆分文件，直接输出正文即可；若选择该结构，请把正文按文件拆分放入 files，"
                + "summary 概括本次产出，并在文件概览后保留 EXECUTION_RECORD 回填块。\n");

        return sb.toString();
    }

    /**
     * 执行恢复上下文注入（N-007 B1 prompt 续接 + B3 Resume 事件流事实）：
     * 重派后以「接续者」视角重新完成任务；无中断摘要零注入。
     *
     * <p><b>Resume 语义边界</b>：本方法提供的是 <b>Prompt 级结构化续接</b>——把「已完成的事实」
     * 以结构化清单注入接续期 prompt，由接续者自行避免重复；<b>并非</b> ADR-001 定义的
     * Step 级续跑（「不重跑已成功的 Step」需 AgentLoop 以既有 messages 为起点续跑 +
     * Step 状态映射，ADR-001 §8 明确留待 Phase 2）。</p>
     *
     * @param completedTrace 中断 Turn 的事件流轨迹（B3 Resume 事实来源）；空/null 时零注入
     */
    private void appendRecoveryContext(StringBuilder sb, AgentSessionService.InterruptedSession recovery,
                                       List<AgentEventTraceItem> completedTrace) {
        if (recovery == null) {
            return;
        }
        sb.append("\n---\n\n");
        sb.append("## 执行恢复上下文（接续执行）\n");
        sb.append("本任务之前的一次执行尝试被中断，本次为接续执行。上次尝试中断情况：\n");
        sb.append("- 会话 ID：").append(recovery.sessionId()).append("\n");
        sb.append("- 中断点：").append(stepLabel(recovery.step())).append("\n");
        sb.append("- 中断状态：").append(statusLabel(recovery.status())).append("\n");
        if (recovery.error() != null && !recovery.error().isBlank()) {
            sb.append("- 中断原因摘要：").append(recovery.error()).append("\n");
        }
        Map<String, Object> snapshot = recovery.snapshot();
        if (snapshot != null && !snapshot.isEmpty()) {
            sb.append("上次执行上下文装配事实：\n");
            Object skills = snapshot.get("skills");
            if (skills instanceof List<?> skillList && !skillList.isEmpty()) {
                sb.append("- 声明技能：").append(skillList.stream()
                        .map(Object::toString).collect(Collectors.joining("、"))).append("\n");
            }
            Object tools = snapshot.get("tools");
            if (tools instanceof List<?> toolList && !toolList.isEmpty()) {
                sb.append("- 启用工具：").append(toolList.stream()
                        .map(Object::toString).collect(Collectors.joining("、"))).append("\n");
            }
            Object environment = snapshot.get("environment");
            if (environment instanceof String envStr && !envStr.isBlank()) {
                sb.append("- 执行环境：").append(envStr).append("\n");
            }
            Object depCount = snapshot.get("depCount");
            if (depCount instanceof Number depNum && depNum.intValue() > 0) {
                sb.append("- 依赖装载：声明 ").append(depNum.intValue()).append(" 条");
                Object loadedCount = snapshot.get("loadedCount");
                Object truncatedCount = snapshot.get("truncatedCount");
                if (loadedCount instanceof Number || truncatedCount instanceof Number) {
                    sb.append("（实际装载 ");
                    sb.append(loadedCount instanceof Number loadedNum ? loadedNum.intValue() : 0);
                    sb.append(" 条");
                    if (truncatedCount instanceof Number truncatedNum && truncatedNum.intValue() > 0) {
                        sb.append("，截断 ").append(truncatedNum.intValue()).append(" 条");
                    }
                    sb.append("）");
                }
                sb.append("\n");
            }
            // 循环进度事实（P0-C checkpoint）：已完成轮数/工具执行（V66 边界：不回复消息历史）
            Object loop = snapshot.get("loop");
            if (loop instanceof Map<?, ?> loopMap) {
                Object iteration = loopMap.get("iteration");
                if (iteration instanceof Number iterationNum && iterationNum.intValue() > 0) {
                    sb.append("- 上次循环进度：已完成 ").append(iterationNum.intValue()).append(" 轮 LLM 调用");
                    Object toolCallCount = loopMap.get("toolCallCount");
                    if (toolCallCount instanceof Number toolCount && toolCount.intValue() > 0) {
                        sb.append("、").append(toolCount.intValue()).append(" 次工具执行");
                    }
                    sb.append("\n");
                    Object executedTools = loopMap.get("executedTools");
                    if (executedTools instanceof List<?> executedList && !executedList.isEmpty()) {
                        sb.append("- 已执行工具：").append(executedList.stream()
                                .map(Object::toString).collect(Collectors.joining("、"))).append("\n");
                    }
                }
            }
        }
        // B3 Resume：事件流已完成事实（Step 槽位 + 已成功工具调用及结果摘要），无事件零注入
        appendCompletedStepFacts(sb, completedTrace);
        sb.append("请以接续者身份结合上述上下文重新完成本任务，输出交付物并按协议回填，不要复述以上中断事实。\n");
    }

    /**
     * 已完成事实块（B3 Resume·Prompt 级结构化续接）：从事件流提取「本 Turn 已记录的 Step
     * 事件槽位」与「已成功工具调用及结果摘要」，向接续者显式声明「无需重复」的工作。
     *
     * <p>事实来源为 {@code agent_event}（ADR-001 Run/Turn/Step 模型的事件侧权威投影）；
     * 工具结果摘要取 {@code TOOL_CALL_COMPLETED.payload.output}（埋点侧已截断 500 字符，
     * 此处再取首行限长防刷屏）。轨迹为空时零注入，不产出空标题。</p>
     */
    private void appendCompletedStepFacts(StringBuilder sb, List<AgentEventTraceItem> completedTrace) {
        if (completedTrace == null || completedTrace.isEmpty()) {
            return;
        }
        List<AgentEventTraceItem> toolCompletions = completedTrace.stream()
                .filter(item -> AgentEventType.TOOL_CALL_COMPLETED.code().equals(item.getEventType()))
                .toList();
        List<String> stepTypes = completedTrace.stream()
                .map(AgentEventTraceItem::getEventType)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (stepTypes.isEmpty() && toolCompletions.isEmpty()) {
            return;
        }
        sb.append("\n### 已完成的事实（无需重复）\n");
        if (!stepTypes.isEmpty()) {
            sb.append("- 本 Turn 已记录的 Step 事件：").append(String.join("、", stepTypes)).append("\n");
        }
        if (!toolCompletions.isEmpty()) {
            sb.append("- 已成功工具调用（按执行序，结果摘要）：\n");
            for (AgentEventTraceItem item : toolCompletions) {
                Map<String, Object> payload = item.getPayload();
                String tool = payload != null && payload.get("tool") != null
                        ? String.valueOf(payload.get("tool")) : "unknown";
                boolean failed = payload != null && Boolean.FALSE.equals(payload.get("success"));
                String output = payload != null && payload.get("output") != null
                        ? String.valueOf(payload.get("output")) : "";
                sb.append("  - ").append(tool).append(failed ? "（失败）" : "（成功）");
                String summary = summarizeLine(output);
                if (!summary.isBlank()) {
                    sb.append("：").append(summary);
                }
                sb.append("\n");
            }
        }
    }

    /** 取文本首个非空行并限长 120 字符（工具结果摘要渲染用）。 */
    private static String summarizeLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String firstLine = text.strip().lines().findFirst().orElse("").strip();
        return firstLine.length() <= 120 ? firstLine : firstLine.substring(0, 120) + "…";
    }

    /** 中断点（step）语义标签：2=LLM 前 / 4=LLM 完成回写前 / 其他原样。 */
    private static String stepLabel(int step) {
        if (step == 2) {
            return "step=2（上下文装配已完成，LLM 调用尚未开始）";
        }
        if (step == 4) {
            return "step=4（LLM 调用已完成，结果回写前中断）";
        }
        return "step=" + step;
    }

    /** 中断状态语义标签：ABORTED=租约回收 / FAILED=执行失败 / 其他原样。 */
    private static String statusLabel(String status) {
        if (status == null) {
            return "UNKNOWN";
        }
        if ("ABORTED".equals(status)) {
            return "ABORTED（租约过期被回收中断）";
        }
        if ("FAILED".equals(status)) {
            return "FAILED（上次执行失败）";
        }
        return status;
    }

    /** 返工上下文注入：从 {@code sub_task.context.reviewHistory} 按轮次铺开 REVIEWER 历史审核意见。 */
    private void appendReworkContext(StringBuilder sb, SubTaskSnapshot subTask) {
        Map<String, Object> ctx = subTask.context();
        if (ctx == null) {
            return;
        }
        Object historyObj = ctx.get("reviewHistory");
        List<?> history = null;
        if (historyObj instanceof List<?> historyList && !historyList.isEmpty()) {
            history = historyList;
        } else if (ctx.get("lastAutoReview") instanceof Map<?, ?> legacy) {
            history = List.of(legacy);
        }
        if (history == null) {
            return;
        }

        sb.append("\n---\n\n");
        sb.append("## 返工修正指引（共 ").append(history.size()).append(" 轮历史审核）\n");
        sb.append("你之前提交被 REVIEWER 多次驳回，请按以下历史审核意见逐轮修正：\n\n");

        int round = 1;
        for (Object item : history) {
            if (!(item instanceof Map<?, ?> review)) {
                continue;
            }
            sb.append("### 第 ").append(round++).append(" 轮\n");
            Object ts = review.get("ts");
            if (ts instanceof String tsStr && !tsStr.isBlank()) {
                sb.append("- 时间: ").append(tsStr).append("\n");
            }
            Object issues = review.get("issues");
            if (issues instanceof List<?> issueList && !issueList.isEmpty()) {
                sb.append("- 审核问题:\n");
                for (Object issue : issueList) {
                    sb.append("  - ").append(issue).append("\n");
                }
            } else if (issues instanceof String issueStr && !issueStr.isBlank()) {
                sb.append("- 审核问题: ").append(issueStr).append("\n");
            }
            Object comment = review.get("comment");
            if (comment instanceof String commentStr && !commentStr.isBlank()) {
                sb.append("- 审核评语: ").append(commentStr).append("\n");
            }
            Object score = review.get("score");
            if (score instanceof Number n) {
                sb.append("- 审核评分: ").append(n.intValue()).append(" / 5\n");
            }
            Object done = review.get("executorDoneIssues");
            if (done instanceof List<?> doneList && !doneList.isEmpty()) {
                String joined = doneList.stream()
                        .map(Object::toString)
                        .collect(Collectors.joining("、"));
                sb.append("- 上一轮你已自认修复: ").append(joined).append("\n");
            }
            sb.append("\n");
        }

        sb.append("请务必针对未自认修复的问题继续修正后重新提交。\n");
    }

    /** 按 dependsOnIdList 收集直接前置的依赖产出参考段（双轨：结构化摘要 + 内容本体）。 */
    private DependencySectionResult buildDependencySection(SubTaskSnapshot subTask) {
        List<Long> dependsOn = subTask.dependsOn();
        if (dependsOn == null || dependsOn.isEmpty()) {
            return DependencySectionResult.empty();
        }
        try {
            List<SubTaskSnapshot> deps = subTaskQueryPort.listByIds(dependsOn);
            Map<Long, SubTaskSnapshot> depMap = new HashMap<>();
            if (deps != null) {
                for (SubTaskSnapshot dep : deps) {
                    depMap.put(dep.id(), dep);
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## 依赖产出参考（直接前置）\n");
            sb.append("你必须综合参考以下前置子任务已完成的内容，结合当前任务要求综合分析后执行：\n\n");

            int loadedCount = 0;
            int truncatedCount = 0;
            int idx = 1;
            for (Long depId : dependsOn) {
                SubTaskSnapshot dep = depMap.get(depId);
                if (dep == null) {
                    continue;
                }
                // 只取摘要字符串：TaskRunningSpecPort 只暴露 summary（不引入 ExecutionRecord 类型）
                String depSummary = taskRunningSpecPort.findExecutionSummary(subTask.taskId(), depId);
                sb.append("### 前置 ").append(idx++).append("：").append(dep.title())
                        .append("（状态：").append(dep.status() != null ? dep.status() : "UNKNOWN")
                        .append("）\n");
                if (depSummary != null && !depSummary.isBlank()) {
                    sb.append("**产出摘要**: ").append(depSummary).append('\n');
                }
                String content = loadUpstreamContent(dep);
                if (content == null || content.isBlank()) {
                    sb.append("（该前置子任务无可用产出内容）\n\n");
                    continue;
                }
                loadedCount++;
                sb.append("**内容**:\n");
                // P-1 防御：截断升级为「行边界回退 + 结构化标注 + 缺失声明指引」——
                // 原实现硬切 substring(0, 4000) 会拦腰截断 URL/清单，且消费方无法机读缺了什么
                String render = TextTruncator.truncateAtLineBoundary(content, DEP_CONTENT_MAX_CHARS);
                boolean topLevelTruncated = render.length() < content.length();
                // R2（2026-09-30 审计 §15.4）：附件路径的逐附件截断已由 UpstreamAttachmentRenderer
                // 内置 [TRUNCATED] file= 标注（总长受控故顶层不再触发）——统计口径并入，
                // timeline 的 truncatedCount 如实反映「该前置存在不可见内容」
                boolean anyTruncated = topLevelTruncated || render.contains("[TRUNCATED] file=");
                if (anyTruncated) {
                    truncatedCount++;
                }
                sb.append(render).append('\n');
                if (topLevelTruncated) {
                    sb.append("\n[TRUNCATED] shown=").append(render.length())
                            .append(" total=").append(content.length())
                            .append(" reason=dep_content_limit\n");
                    sb.append("（提示：本前置产出超限未完整注入。若你的验收依赖不可见部分，"
                            + "必须在交付物中显式声明缺失项，不得臆测补全）\n");
                }
                sb.append('\n');
            }
            return new DependencySectionResult(sb.toString(), dependsOn.size(), loadedCount, truncatedCount, false);
        } catch (Exception e) {
            log.warn("依赖产出上下文装配失败，降级跳过注入: subTaskId={}, err={}", subTask.id(), e.getMessage());
            return DependencySectionResult.degraded(dependsOn.size());
        }
    }

    /**
     * 读取前置子任务的完成内容本体：物化附件优先，失败/无附件回退 context.lastExecution.output。
     *
     * <p><b>P-1 修复（2026-09-30）</b>：原实现按 {@code listActive} 倒序（最新在前）只取
     * 第一个可加载附件——后创建的附录类文件会把先创建的主文件挤出（tku-e2e-01 下游关键词
     * 缺失事故根因）。现改为：反转正序（最早创建在前，主文件优先）+ 拼接全部可加载 ACTIVE
     * 附件（各带 {@code 【文件：xxx】} 来源标题行），单附件读取失败仅跳过不拖垮其余。</p>
     *
     * <p><b>P-1 修复 R2（2026-09-30 审计 §15.4）</b>：拼接改为
     * {@link UpstreamAttachmentRenderer} 逐附件配额渲染（主附件保底 + 次要最低配额 +
     * 逐附件 {@code [TRUNCATED] file=...} 标注）——替代「拼接后单点 4000 截断」，
     * 第二个及以后附件不再整体不可见。</p>
     */
    private String loadUpstreamContent(SubTaskSnapshot dep) {
        try {
            List<AttachmentRef> attachments = attachmentPort.listActive(dep.id());
            if (attachments != null && !attachments.isEmpty()) {
                List<AttachmentRef> ordered = new ArrayList<>(attachments);
                Collections.reverse(ordered);
                List<UpstreamAttachmentRenderer.LoadedAttachment> loaded = new ArrayList<>();
                for (AttachmentRef attachment : ordered) {
                    try {
                        if (!attachment.contentLoadable()) {
                            continue;
                        }
                        byte[] bytes = attachmentPort.loadContent(attachment.id());
                        if (bytes == null || bytes.length == 0) {
                            continue;
                        }
                        String fileName = attachment.fileName() != null && !attachment.fileName().isBlank()
                                ? attachment.fileName() : "attachment-" + attachment.id();
                        loaded.add(new UpstreamAttachmentRenderer.LoadedAttachment(
                                fileName, new String(bytes, StandardCharsets.UTF_8)));
                    } catch (Exception singleEx) {
                        log.warn("读取单个前置附件失败，跳过该附件: subTaskId={}, attachmentId={}, err={}",
                                dep.id(), attachment.id(), singleEx.getMessage());
                    }
                }
                if (!loaded.isEmpty()) {
                    return UpstreamAttachmentRenderer.render(loaded, DEP_CONTENT_MAX_CHARS);
                }
            }
        } catch (Exception e) {
            log.warn("读取前置物化附件内容失败，回退原始产出: subTaskId={}, err={}",
                    dep.id(), e.getMessage());
        }
        return SubTaskOutputExtractor.extractExecutionOutput(dep.context());
    }

    /** 启用工具并集（G-004 增量 A）：命令 tools ∪ 命中技能 requiredTools（去重保序，纯函数式）。 */
    private static List<String> mergeTools(List<String> tools, List<String> skillRequiredTools) {
        if (skillRequiredTools == null || skillRequiredTools.isEmpty()) {
            return tools != null ? tools : List.of();
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (tools != null) {
            merged.addAll(tools);
        }
        merged.addAll(skillRequiredTools);
        return List.copyOf(merged);
    }

    /** 依赖产出段装配结果：渲染文本 + 可观测统计。 */
    private static final class DependencySectionResult {
        private final String section;
        private final int depCount;
        private final int loadedCount;
        private final int truncatedCount;
        private final boolean degraded;

        private DependencySectionResult(String section, int depCount, int loadedCount, int truncatedCount,
                                        boolean degraded) {
            this.section = section;
            this.depCount = depCount;
            this.loadedCount = loadedCount;
            this.truncatedCount = truncatedCount;
            this.degraded = degraded;
        }

        private static DependencySectionResult empty() {
            return new DependencySectionResult("", 0, 0, 0, false);
        }

        private static DependencySectionResult degraded(int depCount) {
            return new DependencySectionResult("", depCount, 0, 0, true);
        }
    }

    // #endregion Prompt 装配

    private static Map<String, Object> safeMap(Object... keyValues) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            if (key instanceof String keyString) {
                result.put(keyString, keyValues[i + 1]);
            }
        }
        return result;
    }
}