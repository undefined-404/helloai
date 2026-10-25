package com.helloai.core.agent.service;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentEventType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.TimelineEventCode;
import com.helloai.common.constant.UncertaintyKind;
import com.helloai.core.agent.AgentLlmCredentialResolver;
import com.helloai.core.agent.chat.AgentProviderResolver;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.event.AgentEventContextResolver;
import com.helloai.core.agent.event.AgentEventQueryService;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.event.AgentEventTraceItem;
import com.helloai.core.agent.event.RecoveryPoint;
import com.helloai.core.agent.quality.service.AgentQualityProfileService;
import com.helloai.core.agent.runtime.AgentContext;
import com.helloai.core.agent.runtime.AgentExecutionResult;
import com.helloai.core.agent.runtime.ExecutionEnvironment;
import com.helloai.core.agent.runtime.loop.LoopCheckpointListener;
import com.helloai.core.agent.session.service.AgentSessionService;
import com.helloai.core.agent.skill.AgentSkillSpecService;
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
import com.helloai.core.agent.tool.EnabledToolNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
 * （{@code SubTaskCommandPort#startIfNeeded}，原 {@code SubTaskExecutionService} 已随 G-002 删除）
 * 与结果回写（{@link ExecutionResultHandler}），
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

    // 单条前置产出注入的字符上限：**口径单源**，见
    // UpstreamAttachmentRenderer.DEP_CONTENT_MAX_CHARS（该类是两个消费方共用的预算算法宿主）。
    // REF-5.2a（2026-10-10）：此前本类与 McpToolServiceImpl 各写一份 64000（且注释互相指认已删除的
    // SubTaskExecutionService/Impl），属典型的口径漂移风险 —— 现统一引用单源。

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
        RecoveryContext recovery = resolveRecoveryContext(subTaskId);
        // B3 Resume：仅重派接续场景（recovery 命中）才查事件流，无中断时不产生额外查询
        List<AgentEventTraceItem> completedTrace = recovery.injected()
                ? findTraceSafely(subTaskId, recovery.turn()) : List.of();
        String userPrompt = buildUserPrompt(subTask, promptSection, dependencySection.section, recovery,
                completedTrace);

        // 2) 启用工具并集（命令 tools ∪ 命中技能 requiredTools，与旧链同语义）
        // REF-1.3：此处**刻意不做** ToolRegistry.resolve——生效形态（条件可用摘除 + 动态描述）
        // 的唯一接线点是 RuntimeTurnExecutor（resolve 的结果决定模型可见工具）。
        // 本类保留的 enabledTools 是**请求集**，用于会话快照与 AgentContext.tools。
        List<String> enabledTools = mergeTools(tools, resolved.requiredTools());

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
        taskTimelinePort.recordEvent(subTask.taskId(), subTaskId, TimelineEventCode.SUB_TASK_SPEC_CONTEXT_LOADED.code(),
                AgentRole.EXECUTOR, agent.getId(),
                safeMap("agentId", agent.getId(),
                        "depCount", dependencySection.depCount,
                        "loadedCount", dependencySection.loadedCount,
                        "truncatedCount", dependencySection.truncatedCount,
                        "degraded", dependencySection.degraded,
                        "pluginSpec", resolved.section() != null && !resolved.section().isBlank(),
                        "historySummary", historySection != null && !historySection.isBlank(),
                        // recoveryInjected 语义不变（是否注入了恢复上下文）；
                        // recoverySource 为 REF 消费面新增：区分来源 session（会话表）/ event（事件流兜底）
                        "recoveryInjected", recovery.injected(),
                        "recoverySource", recovery.source()));
        taskTimelinePort.recordEvent(subTask.taskId(), subTaskId, TimelineEventCode.SUB_TASK_LLM_CALL_START.code(),
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
                .systemPrompt(buildSystemPrompt(agent, command.getAccessType()))
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
        taskTimelinePort.recordEvent(subTask.taskId(), subTask.id(), TimelineEventCode.SUB_TASK_LLM_CALL_END.code(),
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
     * 解析恢复上下文：{@code agent_session} 中断记录<b>优先</b>，缺失时以<b>事件流恢复点兜底</b>。
     *
     * <p><b>兜底的必要性（实测）</b>：会话表是可变表，且查询口径为「只看最新一行，
     * ACTIVE / COMPLETED 一律零注入」（{@code AgentSessionServiceImpl#findLatestInterrupted}）。
     * 进程执行中途被中断（优雅停机 / kill / OOM）时该行可能停在 ACTIVE，或根本没有行
     * ⇒ 续跑上下文<b>静默零注入</b>，接续者不知道上次已经做过什么，可能重复副作用。
     * 此时事件流里「{@code AGENT_STARTED} 之后没有 {@code AGENT_COMPLETED}」以及
     * 「{@code TOOL_CALL_STARTED} 之后没有 {@code TOOL_CALL_COMPLETED}」是 append-only
     * 的中断点证据，不会被任何后续写入覆盖。</p>
     *
     * <p><b>纪律</b>：纯读、零写入 —— 不修正会话表、不修正业务状态；事件流只作事实来源，
     * 不作第二业务状态源（{@code design/Agent_Event_Stream.md} 原则 2）。</p>
     */
    private RecoveryContext resolveRecoveryContext(Long subTaskId) {
        AgentSessionService.InterruptedSession session = findRecoverySafely(subTaskId);
        if (session != null) {
            return new RecoveryContext(session, null);
        }
        RecoveryPoint point = projectRecoveryPointSafely(subTaskId);
        if (point == null || !point.isUnfinished()) {
            return RecoveryContext.none();
        }
        return new RecoveryContext(null, point);
    }

    /** 事件流恢复点安全查询：查询异常一律降级为 null（零注入零阻断）。 */
    private RecoveryPoint projectRecoveryPointSafely(Long subTaskId) {
        try {
            return agentEventQueryService.projectRecoveryPoint(subTaskId);
        } catch (Exception e) {
            log.debug("事件流恢复点查询失败（best-effort 降级，零注入）: subTaskId={}, err={}",
                    subTaskId, e.getMessage());
            return null;
        }
    }

    /** 恢复上下文注入来源：{@code agent_session} 表（租约回收写入的中断点）。 */
    private static final String RECOVERY_SOURCE_SESSION = "session";

    /** 恢复上下文注入来源：事件流恢复点兜底（会话表无可用中断记录）。 */
    private static final String RECOVERY_SOURCE_EVENT = "event";

    /**
     * 恢复上下文（B3 Resume 与 Recovery 消费面的统一载体）。
     *
     * <p>两个来源<b>互斥且会话表优先</b>：{@code session} 非空时不用 {@code eventPoint}
     * （会话表带 snapshot，信息更全）；二者皆空 = 本次为首次执行或上次已闭合 ⇒ 零注入。</p>
     *
     * @param session    会话表中断记录；无则 {@code null}
     * @param eventPoint 事件流恢复点（兜底）；无则 {@code null}
     */
    private record RecoveryContext(AgentSessionService.InterruptedSession session, RecoveryPoint eventPoint) {

        /** 零注入（首次执行 / 上次已正常闭合）。 */
        private static RecoveryContext none() {
            return new RecoveryContext(null, null);
        }

        /** 是否命中恢复上下文（命中才查事件流轨迹，无中断时不产生额外查询）。 */
        boolean injected() {
            return session != null || eventPoint != null;
        }

        /** 注入来源（可观测字段 {@code recoverySource}）；未注入时为 {@code null}。 */
        String source() {
            if (session != null) {
                return RECOVERY_SOURCE_SESSION;
            }
            return eventPoint != null ? RECOVERY_SOURCE_EVENT : null;
        }

        /**
         * 续跑参考的轮次序号：会话来源取 {@code turn}，事件来源取未闭合轮次。
         *
         * @return 轮次序号；两种来源都取不到时返回 0（{@code findTraceSafely} 对 0 无匹配
         *         ⇒ 退回全量轨迹，与既有降级口径一致）
         */
        int turn() {
            if (session != null) {
                return session.turn();
            }
            return eventPoint != null && eventPoint.getUnfinishedTurn() != null
                    ? eventPoint.getUnfinishedTurn() : 0;
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
                                   RecoveryContext recovery,
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
                // kind 值域单源在 helloai-common（agent 可直依，无实体泄漏、无字面量复制）；
                // assumption 布尔仍由 task 侧映射器派生，与 kind 枚举判定等价（TD-7）
                String suffix = switch (UncertaintyKind.parse(u.kind())) {
                    case ASSUMPTION -> "（可自行验证，推翻即上报）";
                    case TO_LOOK_UP -> "（先检索确认：联网 / 附件 / 知识库；检索不到再 BLOCKED 上报）";
                    case TO_DERIVE -> "（先推导出中间结论再动手；推不出再 BLOCKED 上报）";
                    case UNCONFIRMED -> "（须先验证再动手，无法验证则 BLOCKED 上报）";
                };
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

        // REF-8.2.2：验证证据围栏——与评审侧 fail-close **同源**（ReviewEvidenceAssembler.verificationSignal
        // 只判 `contains("VERIFICATION:")`）。此前内部 LLM 的输出契约不含该段，而评审侧对「无 VERIFICATION」
        // 的提交明文「从严核验、不得判 pass=true」（prompts/subtask-review.md:51-52）⇒ 两边不同源，
        // 内循环必然返工至死信（LOG-20261012-007 实测复现 2 次）。形状照抄 DSH Ralph 轮报
        // （证据 + 状态 + 下一步，研究文档 §1.5.4）。
        sb.append("\n## 验证证据围栏（必须放在全文最后）\n");
        sb.append("评审会检测 `VERIFICATION:` 围栏；**缺失将被从严核验**（仅凭正文无法确认验收标准时会判不通过）。")
                .append("请在交付物正文与 EXECUTION_RECORD 之后、**全文最末**附上：\n\n");
        sb.append("```\n");
        sb.append("VERIFICATION:\n");
        sb.append("- 证据1：<你实际读到的原文片段 / 工具返回的关键内容，原样粘贴，禁止转述>\n");
        sb.append("- 证据2：<...>\n");
        sb.append("STATUS: DONE | PARTIAL | BLOCKED\n");
        sb.append("NEXT_STEPS:\n");
        sb.append("- <若未完成：下一步缺什么 / 需要谁补；DONE 时写「无」>\n");
        sb.append("```\n");
        sb.append("规则：① 该围栏之后**不得**再写任何内容；② `STATUS: BLOCKED` **仅**用于你已调用 ")
                .append("internal_report_blocked 的情形，不要用文本代替工具报阻塞；③ 只完成一部分用 PARTIAL，")
                .append("并在 NEXT_STEPS 写清缺什么。\n");

        return sb.toString();
    }

    /**
     * 执行恢复上下文注入（N-007 B1 prompt 续接 + B3 Resume 事件流事实）：
     * 重派后以「接续者」视角重新完成任务；无中断摘要零注入。
     *
     * <p><b>Resume 语义边界</b>：本方法提供的是 <b>Prompt 级结构化续接</b>——把「已完成的事实」
     * 以结构化清单注入接续期 prompt，由接续者自行避免重复；<b>并非</b> ADR-001 定义的
     * Step 级续跑（「不重跑已成功的 Step」需 AgentLoop 以既有 messages 为起点续跑 +
     * Step 状态映射，ADR-001 §8 明确后置）。</p>
     *
     * @param completedTrace 中断 Turn 的事件流轨迹（B3 Resume 事实来源）；空/null 时零注入
     */
    private void appendRecoveryContext(StringBuilder sb, RecoveryContext recovery,
                                       List<AgentEventTraceItem> completedTrace) {
        if (recovery == null || !recovery.injected()) {
            return;
        }
        sb.append("\n---\n\n");
        sb.append("## 执行恢复上下文（接续执行）\n");
        sb.append("本任务之前的一次执行尝试被中断，本次为接续执行。上次尝试中断情况：\n");
        if (recovery.session() != null) {
            appendSessionInterruptFacts(sb, recovery.session());
        } else {
            appendEventInterruptFacts(sb, recovery.eventPoint());
        }
        // B3 Resume：事件流已完成事实（Step 槽位 + 已成功工具调用及结果摘要），无事件零注入
        appendCompletedStepFacts(sb, completedTrace);
        sb.append("请以接续者身份结合上述上下文重新完成本任务，输出交付物并按协议回填，不要复述以上中断事实。\n");
    }

    /**
     * 会话来源中断事实块：来自 {@code agent_session}（租约回收写入的中断点，含上下文快照）。
     */
    private void appendSessionInterruptFacts(StringBuilder sb, AgentSessionService.InterruptedSession recovery) {
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
    }

    /**
     * 事件来源中断事实块（Recovery 消费面兜底）。
     *
     * <p>会话表无可用中断记录时启用：全部事实由 {@code agent_event} 投影得到，
     * <b>不编造</b>会话 ID / 中断状态等会话侧字段（不注入无来源的信息）。</p>
     *
     * <p>渲染口径：{@code danglingTool} 是中断点本身（{@code TOOL_CALL_STARTED} 未配对），
     * {@code completedTools} 是接续者「无需重复」的已完成工具；两者互补，
     * 恰好回答续跑最关心的两个问题 ——「断在哪」与「做到哪」。</p>
     */
    private void appendEventInterruptFacts(StringBuilder sb, RecoveryPoint point) {
        if (point == null) {
            return;
        }
        sb.append("- 事实来源：执行事件流（append-only），会话表无可用中断记录\n");
        if (point.getUnfinishedTurn() != null) {
            sb.append("- 未闭合轮次：第 ").append(point.getUnfinishedTurn()).append(" 轮\n");
        }
        if (point.getDanglingTool() != null) {
            sb.append("- 中断点：工具「").append(point.getDanglingTool())
                    .append("」已开始、未见完成\n");
        }
        if (point.getCompletedTools() != null && !point.getCompletedTools().isEmpty()) {
            sb.append("- 该轮已完成工具：").append(String.join("、", point.getCompletedTools())).append("\n");
        }
        if (point.getLastEventType() != null) {
            sb.append("- 事件流最后事件：").append(point.getLastEventType());
            if (point.getLastEventAt() != null) {
                sb.append("（").append(point.getLastEventAt()).append("）");
            }
            sb.append("\n");
        }
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

    /**
     * 返工上下文注入（REF-8.2.1：**替换语义**，非追加）。
     *
     * <p>此前把<b>全部</b>历史轮次逐轮铺开（"共 N 轮历史审核"，每轮含问题/评语/评分）——返工到第 3 轮时
     * prompt 里堆着 3 份完整驳回 + 前置摘要 + 上次产出，思考链与 token 随轮数线性膨胀，而<b>历史轮次
     * 对本轮该改什么几乎没有增量信息</b>（`LOG-20261012-007` 实测：模型对"能力上做不到"的任务只是
     * 重复同一结论，回灌历史驳回零收益）。</p>
     *
     * <p>现改为与 DSH 一致的<b>替换语义</b>（`refreshContext` 替换而非追加、运行期快照显式声明
     * "supersedes earlier snapshots"——研究文档 §1.5.2）：<b>只铺开最近一轮</b>，历史轮次压成一行
     * 摘要并明写「已被本轮取代」。</p>
     */
    private void appendReworkContext(StringBuilder sb, SubTaskSnapshot subTask) {
        Map<String, Object> ctx = subTask.context();
        if (ctx == null) {
            return;
        }
        List<?> history = resolveReviewHistory(ctx);
        if (history == null || history.isEmpty()) {
            return;
        }
        int latestIdx = lastReviewIndex(history);
        if (latestIdx < 0) {
            return;
        }
        Map<?, ?> latest = (Map<?, ?>) history.get(latestIdx);

        sb.append("\n---\n\n");
        sb.append("## 返工修正指引（第 ").append(history.size()).append(" 轮驳回）\n");
        if (latestIdx > 0) {
            sb.append("> 此前 ").append(latestIdx)
                    .append(" 轮意见**已被本轮取代**（历史摘要：")
                    .append(summarizeSuperseded(history, latestIdx))
                    .append("）——以本轮意见为准，不要再按旧轮意见返工。\n\n");
        }
        sb.append("### 本轮审核意见\n");
        appendReviewRound(sb, latest);
        sb.append("请只针对**本轮**未自认修复的问题修正后重新提交；已自认修复项不必重复改动。\n");
    }

    /** 取历史审核轮次（新格式 {@code reviewHistory} 优先，旧格式 {@code lastAutoReview} 单轮回退）。 */
    private static List<?> resolveReviewHistory(Map<String, Object> ctx) {
        Object historyObj = ctx.get("reviewHistory");
        if (historyObj instanceof List<?> historyList && !historyList.isEmpty()) {
            return historyList;
        }
        if (ctx.get("lastAutoReview") instanceof Map<?, ?> legacy) {
            return List.of(legacy);
        }
        return null;
    }

    /** 最近一轮（含有效 Map 的最大下标）；无有效轮次返回 -1。 */
    private static int lastReviewIndex(List<?> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof Map) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 被取代的历史轮次压缩为一行（每轮取首条问题、截断；最多列 3 轮，超出以「…」收尾）。
     *
     * <p>这是「增量有界」的保证：无论返工多少轮，返工段的历史部分恒定 ≤ 一行。</p>
     */
    private static String summarizeSuperseded(List<?> history, int latestIdx) {
        StringBuilder s = new StringBuilder();
        int listed = 0;
        for (int i = 0; i < latestIdx; i++) {
            if (!(history.get(i) instanceof Map<?, ?> review)) {
                continue;
            }
            if (listed == 3) {
                s.append("；…");
                break;
            }
            if (listed > 0) {
                s.append("；");
            }
            s.append("第 ").append(i + 1).append(" 轮: ").append(firstIssueBrief(review));
            listed++;
        }
        return s.isEmpty() ? "无" : s.toString();
    }

    /** 取一轮审核的首条问题并截断（用于历史摘要行）。 */
    private static String firstIssueBrief(Map<?, ?> review) {
        Object issues = review.get("issues");
        String first = null;
        if (issues instanceof List<?> issueList && !issueList.isEmpty()) {
            first = String.valueOf(issueList.get(0));
        } else if (issues instanceof String issueStr && !issueStr.isBlank()) {
            first = issueStr;
        }
        if (first == null) {
            return "(无问题描述)";
        }
        String trimmed = first.trim().replaceAll("\\s+", " ");
        return trimmed.length() > 40 ? trimmed.substring(0, 40) + "…" : trimmed;
    }

    /** 铺开一轮审核的明细（时间 / 问题 / 评语 / 评分 / 已自认修复）。 */
    private static void appendReviewRound(StringBuilder sb, Map<?, ?> review) {
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
            sb.append("- 上一轮你已自认修复（不必重复改动）: ").append(joined).append("\n");
        }
        sb.append("\n");
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
                String render = TextTruncator.truncateAtLineBoundary(content, UpstreamAttachmentRenderer.DEP_CONTENT_MAX_CHARS);
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
                                fileName, new String(bytes, StandardCharsets.UTF_8), attachment.id()));
                    } catch (Exception singleEx) {
                        log.warn("读取单个前置附件失败，跳过该附件: subTaskId={}, attachmentId={}, err={}",
                                dep.id(), attachment.id(), singleEx.getMessage());
                    }
                }
                if (!loaded.isEmpty()) {
                    return UpstreamAttachmentRenderer.render(loaded, UpstreamAttachmentRenderer.DEP_CONTENT_MAX_CHARS);
                }
            }
        } catch (Exception e) {
            log.warn("读取前置物化附件内容失败，回退原始产出: subTaskId={}, err={}",
                    dep.id(), e.getMessage());
        }
        return SubTaskOutputExtractor.extractExecutionOutput(dep.context());
    }

    /**
     * 构建系统提示词（REF-8.2 动作①：systemPrompt 从 {@code ""} 填角色 / 工具指引 / 输出契约）。
     *
     * <p>此前 {@code AgentRuntimeContextAssembler:213} 硬编码 {@code systemPrompt("")}——模型只有
     * userPrompt（任务上下文），缺角色边界与「有工具该何时用」的主动指引。本方法补齐：</p>
     * <ul>
     *   <li><b>角色</b>：明确自己是执行者、为任务交付物负责；</li>
     *   <li><b>工具指引</b>：接入类型={@code API_KEY_LLM}（内部 LLM，有进程内工具集）时提示
     *       可经工具读取附件/依赖、无法仅凭预内联摘要做结论时先用工具核验——对齐
     *       {@code LOG-20261011-007} 事故根因（内部 LLM 只吃摘要、不主动读原始附件）；</li>
     *   <li><b>输出契约</b>：正文用 Markdown、交付物具体可核验、行末按 userPrompt 的
     *       {@code EXECUTION_RECORD} 回填要求输出回复。</li>
     * </ul>
     *
     * <p><b>克制 / 不臆造</b>：不注入 Agent 姓名或任务级个性化（那些在 userPrompt）；systemPrompt 是
     * 稳定的角色面，不随任务漂移。external / null 接入类型给通用角色段（不假说有工具）。
     * 返回值恒非 blank，使 {@code ChatModelToolLoop:74} 的 hasText 守卫通过并发送 system 消息。</p>
     *
     * @param agent      执行者 Agent（用于角色声明）
     * @param accessType 接入类型（决定是否提示有工具）
     */
    private String buildSystemPrompt(Agent agent, AgentAccessType accessType) {
        boolean internal = accessType == AgentAccessType.API_KEY_LLM;
        // REF-8.1.2 persona 两槽位（对齐 DSH `deployment:persona-prefix/suffix` + `dsh-persona` 的遮蔽纪律）：
        // 部署级默认（helloai.execution.persona-prefix/suffix）→ 按 Agent 遮蔽（agent.labels 同名键优先）。
        String personaPrefix = resolvePersona(agent, "personaPrefix", executionProperties.getPersonaPrefix());
        String personaSuffix = resolvePersona(agent, "personaSuffix", executionProperties.getPersonaSuffix());
        StringBuilder sb = new StringBuilder();
        sb.append("你是 HelloAI 平台的子任务执行者（Agent: ")
                .append(agent != null && agent.getName() != null ? agent.getName() : "executor")
                .append("）。你的职责是把分派给你的子任务按验收标准完成并交付可核验的成果。\n");
        if (!personaPrefix.isEmpty()) {
            sb.append(personaPrefix).append("\n");
        }
        sb.append("\n");
        if (internal) {
            sb.append("你拥有平台提供的进程内工具集，可用来：\n")
                    .append("- 读取当前子任务的完整详情、验收标准、执行约束（internal_get_sub_task_detail）；\n")
                    .append("- 读取前置依赖产出的附件正文（internal_read_attachment / internal_get_deps_summary）；\n")
                    .append("- 在你的正文之外登记独立的交付文件（internal_register_output_attachment）；\n")
                    .append("- 查看平台全部可用技能目录（internal_list_skills），并按需加载某个技能的完整规范正文")
                    .append("（internal_load_skill）——任务涉及特定专业规范时，先查技能库再按规范执行。\n")
                    // ★2026-10-25（MiniMax 轮数耗尽事故）：模型把 21211 字节正文写进沙箱 /tmp/work/sc.md
                    // 并反复自查，平台一条都没收到 ⇒ 必须显式告知「沙箱文件不构成交付物」——
                    // 这是模型缺的领域知识，不是措辞强度问题（此前已有「正文优先」仍照写文件）。
                    // ★同日二次修正（ADR-005 沙箱改为同子任务复用）：沙箱不再是「一次性环境」——
                    // 文件跨调用保留、可增量编辑；但「沙箱文件 ≠ 交付物」这条硬约束不变（平台只采集
                    // 对话正文）。两条必须同时说清：只说「一次性」会让模型整篇重写（实测 20 轮不收敛），
                    // 只说「可编辑」会让它以为文件就是交付。
                    .append("- 沙箱（internal_run_in_sandbox）是**本子任务的工作区**：同一子任务内多次调用**复用同一沙箱**，")
                    .append("**上一轮写入的文件这一轮还在**（路径不变即同一文件）。因此：\n")
                    .append("  · 需要改稿时用 `sed -i` / `python` 对**已有文件**做**定点修改**，")
                    .append("**不要每轮把整篇正文重新 heredoc 一遍**——整篇重写既烧轮次，也会让内容漂移；\n")
                    .append("  · 需要多步操作（写文件 → 校验）时，**写在同一条命令里**（`cat > f && wc -c f`）最稳；\n")
                    .append("  · 建议把草稿放在固定路径（如 `/workspace/draft.md`），跨轮沿用。\n")
                    .append("  ⚠ 但**沙箱内文件不是交付物**：平台只采集**对话正文**，")
                    .append("沙箱文件**不会被采集、不进评审**——**交付物正文必须写在你的对话正文里**。")
                    .append("沙箱只用于「落草稿 / 跑校验 / 算一遍」；子任务执行结束后沙箱销毁。\n")
                    .append("当任务需要原始字段 / 实际文件内容，而上下文中仅给了摘要时，应先用工具核验，")
                    .append("不要仅凭摘要臆测补全。确认自己**无法完成**本子任务时（输入/原始附件不可得、")
                    .append("需要本机环境而你没有、验收要求的证据拿不到），调用 internal_report_blocked ")
                    .append("说明「缺什么 / 试过什么 / 需要什么才能继续」——报阻塞会转入人工介入、")
                    .append("不再进入评审；**不要**用正文硬写「做不到」充当交付物（那会被评审打回并反复返工）。\n\n");
        }
        sb.append("输出契约：\n")
                .append("- 用 Markdown 输出交付物正文，具体、可检查、紧扣验收标准；\n")
                .append("- 若任务要求（见任务描述末尾的「产出回填要求」），按 EXECUTION_RECORD 结构回填；\n")
                .append("- **交付物正文必须在工具调研完成后立即写出**：调研 / 检索 / 验证工具调用是手段，")
                .append("正文才是交付物——本轮循环的最后一轮务必输出完整正文，不要把「我先看看 / 开场白」当正文；\n")
                // ★2026-10-25（MiniMax 14 轮耗尽事故）：实测「沙箱写文件 + 反复跑校验脚本」会吃掉全部轮次，
                // 耗尽时正文一个字都没产出。轮次是有限资源，必须显式给出预算口径与优先级。
                .append("- **轮次是有限资源，交付优先**：调研 2~4 轮、成文 1 轮即可，自查 / 自验**最多 1 次**；")
                .append("反复写脚本校验同一条结论会耗尽轮次、导致正文来不及产出而被判失败——")
                .append("**先写出正文永远是第一优先级**，未复核项写进正文末尾的 VERIFICATION 里说明即可；\n")
                // ★2026-10-25（MiniMax 竞品分析 MAX_ITERATIONS 事故）：模型把 20 轮全花在沙箱 curl
                // 逐页抓取 ClickUp 定价页上（18 次沙箱调用、23 次工具调用），正文零产出。
                // 根因：web_search 授权缺失 → 唯一联网通道 = 沙箱 curl；但模型不知道「抓取也有预算」。
                // 必须显式给出「调研抓取轮次上限」与「正文优先」的硬约束。
                .append("- **调研抓取轮次上限**：联网检索 / 沙箱 curl 抓取**最多 6 轮**；")
                .append("第 6 轮后无论数据是否齐，都必须停止抓取、开始写正文——")
                .append("数据缺口写进 VERIFICATION 的 NEXT_STEPS 里说明，不要继续抓；\n")
                .append("- **链接抽查即可**：验收要求「N 条真实可打开来源」时，**抽查 2~3 条验证可达即可**，")
                .append("不必逐条 curl 验证——逐条验证会耗尽轮次；\n")
                // ★2026-10-25（MiniMax 第 6 次事故，7443）：验收要求「正文 1800~2300 字」，模型首稿写 3227 字
                // （超 70%），之后 20 轮只把 3227 削到 3008，每轮 20~50 字，缺口 708 字 ⇒ 数学上不可能收敛。
                // 根因不是模型懒，而是策略错：它先追求「写得好」再「砍得短」。必须让它首稿就按区间写。
                .append("- **字数区间类验收：首稿就要命中，不要「写超再砍」**：验收写「A~B 字」时，")
                .append("按 B 的**下沿**（约 B×0.85）一次写到位；若已写超，用增量删减")
                .append("（`python3` 读文件 → 定位删段/删句 → 写回）**一次删够到区间内**，")
                .append("不要每轮只删几十字——实测该速率（20~50 字/轮）下 900 字缺口需 18~45 轮，必然耗尽轮次；\n")
                .append("- 统计字数时**排除代码块 / Mermaid / 表格原始字段**（除实验收明确要求计入），")
                .append("并把统计命令与输出写进 VERIFICATION，不要只写结论；\n")
                .append("- 结尾必须附 `VERIFICATION:` 验证证据围栏（证据 / STATUS / NEXT_STEPS）——")
                .append("缺失会被评审从严核验，仅凭正文无法确认验收标准时会判不通过；\n")
                .append("- 不确定或能力达不到的事，如实说明，不得伪造文件 / 数据充当交付物。");
        if (!personaSuffix.isEmpty()) {
            sb.append("\n").append(personaSuffix);
        }
        return sb.toString();
    }

    /**
     * REF-8.1.2 persona 槽位取值：**按 Agent 遮蔽部署级默认**。
     *
     * <p>取值顺序（照抄 DSH：部署给默认、单 agent 可遮蔽）：{@code agent.labels[key]}（非空字符串）
     * → {@code deploymentDefault} → {@code ""}（不注入）。key 取 {@code personaPrefix} / {@code personaSuffix}。</p>
     */
    private static String resolvePersona(Agent agent, String key, String deploymentDefault) {
        if (agent != null && agent.getLabels() != null) {
            Object override = agent.getLabels().get(key);
            if (override instanceof String s && !s.isBlank()) {
                return s.trim();
            }
        }
        return deploymentDefault == null ? "" : deploymentDefault.trim();
    }

    /** 启用工具并集（G-004 增量 A）：命令 tools ∪ 命中技能 requiredTools（去重保序，纯函数式）。 */
    private static List<String> mergeTools(List<String> tools, List<String> skillRequiredTools) {
        return EnabledToolNames.union(tools, skillRequiredTools);
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