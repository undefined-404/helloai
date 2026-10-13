package com.helloai.core.agent.runtime.loop.impl;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentEventType;
import com.helloai.core.agent.chat.ChatResponseContentExtractor;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.runtime.loop.AgentLoop;
import com.helloai.core.agent.runtime.loop.AgentLoopInput;
import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import com.helloai.core.agent.runtime.loop.LoopCheckpoint;
import com.helloai.core.agent.runtime.loop.LoopCheckpointListener;
import com.helloai.core.agent.tool.ToolExecutionResult;
import com.helloai.core.agent.tool.ToolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link AgentLoop} 真身——手动工具循环（spring-ai ChatModel 契约，provider 无关）。
 *
 * <p>核心机制：{@code DefaultToolCallingChatOptions.setInternalToolExecutionEnabled(false)}
 * 让模型只返回工具调用请求（不自动执行），由本类经 {@link ToolExecutor}（执行回路）
 * 执行并把 {@link ToolResponseMessage} 回喂给模型，循环至模型输出终态文本。</p>
 *
 * <p>事件：每次工具调用按 ADR-001 记 TOOL_CALL_STARTED（step=3）/ TOOL_CALL_COMPLETED（step=4），
 * payload 含工具名与入参/结果；write-only 纪律——记录失败仅告警不阻断循环。</p>
 *
 * <p>终止保证：maxIterations 硬上限（默认 5）防死循环；达到上限返回末轮正文的
 * MAX_ITERATIONS 结果。真实 provider 的工具调用行为需在 Runtime 真身接线时联调
 * （本构件以确定性 stub ChatModel 单测验证契约与循环逻辑）。</p>
 *
 * <p>循环进度检查点（P0-C checkpoint）：每轮「工具执行与回喂完成、下一轮 LLM 未开始」
 * 边界经 {@link LoopCheckpointListener} 回调落库（V66 边界：只落进度事实，不回放消息）；
 * write-only 纪律——回调缺失跳过、失败仅告警，不阻断循环。</p>
 *
 * <p>Token 用量采集（B3 tokenUsage）：每轮 LLM 调用后读取 {@code response.metadata.usage.totalTokens}
 * 并累加，经 {@link AgentLoopResult#tokenUsage()} 透出；provider 未返回 usage 时忽略
 * （best-effort，不阻断循环，全程无 usage 则恒 null）。</p>
 */
@Slf4j
@Component
public class ChatModelToolLoop implements AgentLoop {

    /** 事件 payload 中工具输出截断长度（防大输出刷爆事件表）。 */
    private static final int OUTPUT_TRUNCATE_CHARS = 500;

    /** 上下文压缩器（REF-8.2.3）；no-op 时行为与改造前逐字一致。 */
    private final ContextCompactor compactor;

    /** 循环具名挂点链（REF-8.4 骨架）；默认注册 {@link CompactionHook}（压缩行为与改造前一致）。 */
    private final List<LoopHook> hooks;

    /** 单压缩器构造（既有单测 / 无 Spring 场景）：不注入附加挂点（行为与改造前一致）。 */
    public ChatModelToolLoop(ContextCompactor compactor) {
        this(compactor, List.of());
    }

    /**
     * Spring 注入构造（REF-8.2.3 上下文压缩 + REF-8.4 默认压缩挂点 + 策略挂点，对齐 DSH
     * `compaction-basic`）。{@code List<LoopHook>} 注入全部 LoopHook bean——内置压缩挂点恒为首个，
     * 其后追加策略挂点（如 `@Component FinalReportFenceHook`）。附加挂点为空列表 ⇒ 行为逐字一致。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ChatModelToolLoop(ContextCompactor compactor, List<LoopHook> extraHooks) {
        this.compactor = compactor == null ? ContextCompactor.noop() : compactor;
        this.hooks = buildHooks(this.compactor, extraHooks);
    }

    /** 无压缩器构造（既有单测 / 无 Spring 场景）——等价于压缩关闭（CompactionHook(noop) 恒 null）。 */
    public ChatModelToolLoop() {
        this(ContextCompactor.noop(), List.of());
    }

    /** 组装挂点链：压缩挂点恒为首个，其后追加附加挂点。 */
    private static List<LoopHook> buildHooks(ContextCompactor compactor, List<LoopHook> extraHooks) {
        List<LoopHook> result = new ArrayList<>();
        result.add(new CompactionHook(compactor));
        if (extraHooks != null) {
            result.addAll(extraHooks);
        }
        return List.copyOf(result);
    }

    @Override
    public AgentLoopResult run(AgentLoopInput input) {
        if (input.chatModel() == null || input.toolExecutor() == null) {
            return AgentLoopResult.error("agent loop requires chatModel and toolExecutor", 0, 0);
        }
        List<Message> messages = new ArrayList<>();
        // 空 system 消息不发送：部分 provider（moonshot / kimi）对 `role=system` 的空内容
        // 直接返回 400 "the message at position 0 with role 'system' must not be empty"，
        // 而 deepseek / minimax 会忽略——同一份代码在不同 provider 上表现不一致。
        // 与 AgentChatClientServiceImpl#doGenerate 的 hasText 守卫同口径（该链一直有此守卫，
        // AgentLoop 链此前遗漏）。
        if (StringUtils.hasText(input.systemPrompt())) {
            messages.add(new SystemMessage(input.systemPrompt()));
        }
        messages.add(new UserMessage(input.userPrompt() != null ? input.userPrompt() : ""));

        int iterations = 0;
        int toolCallCount = 0;
        Integer totalTokens = null;
        String lastText = "";
        Set<String> executedToolNames = new LinkedHashSet<>();

        int overflowRetries = 0;
        int summarizeBudget = compactor.maxSummarizationsPerRun();
        for (int i = 0; i < input.maxIterations(); i++) {
            iterations = i + 1;
            DefaultToolCallingChatOptions options = buildOptions(input);
            // REF-8.4 挂点骨架：请求前经 beforeRequest 挂点（对齐 DSH `agent/pre-step` 压力触发）
            // 默认 CompactionHook 剪枝 → 仍超预算才摘要（额度受 summarizeBudget 约束）
            CompactionOutcome pressure = runBeforeRequest(input, messages, options, summarizeBudget);
            if (pressure != null && pressure.summarized()) {
                summarizeBudget--;
            }
            recordCompaction(input, i + 1, pressure);
            ChatResponse response;
            while (true) {
                try {
                    response = input.chatModel().call(new Prompt(messages, options));
                    break;
                } catch (RuntimeException e) {
                    // REF-8.4 挂点骨架：溢出恢复经 onRequestError 挂点（对齐 DSH `agent/request-error`）
                    // 压缩一次后重试本轮；超上限或无可压内容则原样抛出（保持改造前的失败语义）
                    if (overflowRetries >= compactor.maxOverflowRetries()) {
                        throw e;
                    }
                    CompactionOutcome fix = runOnRequestError(input, messages, options, summarizeBudget);
                    if (fix == null || !fix.changed()) {
                        throw e;
                    }
                    if (fix.summarized()) {
                        summarizeBudget--;
                    }
                    overflowRetries++;
                    recordCompaction(input, i + 1, fix);
                }
            }
            overflowRetries = 0;   // 成功响应后重置（DSH 同口径：成功 assistant/message 重置溢出计数）
            if (response == null) {
                return AgentLoopResult.error("chat model returned null response", iterations, toolCallCount,
                        totalTokens);
            }
            totalTokens = accumulateUsage(response, totalTokens);
            ChatResponseContentExtractor.ExtractedContent extracted =
                    ChatResponseContentExtractor.extract(response);
            lastText = extracted.text();
            AssistantMessage assistant = response.getResult() != null ? response.getResult().getOutput() : null;
            if (assistant != null) {
                messages.add(assistant);
            }
            if (assistant == null || !assistant.hasToolCalls()) {
                return terminate(input, messages, AgentLoopResult.stop(lastText,
                        extracted.thinking().isBlank() ? null : extracted.thinking(),
                        iterations, toolCallCount, totalTokens));
            }
            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
                if (toolCall == null) {
                    continue;
                }
                ToolExecutionResult result = executeToolSafely(input, toolCall.name(), toolCall.arguments());
                toolCallCount++;
                executedToolNames.add(toolCall.name());
                toolResponses.add(new ToolResponseMessage.ToolResponse(
                        toolCall.id(), toolCall.name(),
                        result.success() ? result.output() : "ERROR: " + result.errorMessage()));
            }
            if (toolResponses.isEmpty()) {
                // 模型发了空工具调用列表（异常响应）：防死循环，按终态返回
                return terminate(input, messages, AgentLoopResult.stop(lastText,
                        extracted.thinking().isBlank() ? null : extracted.thinking(),
                        iterations, toolCallCount, totalTokens));
            }
            messages.add(ToolResponseMessage.builder().responses(toolResponses).build());
            // 循环进度检查点（P0-C checkpoint）：本轮 LLM + 工具均已完成、下一轮未开始；
            // write-only 纪律——落库失败仅告警，不阻断循环（V66 边界：只落进度事实）
            fireCheckpoint(input, new LoopCheckpoint(
                    i + 1, toolCallCount, messages.size(), new ArrayList<>(executedToolNames)));
        }
        return terminate(input, messages, AgentLoopResult.maxIterations(lastText, iterations, toolCallCount,
                totalTokens));
    }

    /** beforeRequest 挂点链分发：恒返回最后一个非 null 结果（默认 CompactionHook 唯一）。 */
    private CompactionOutcome runBeforeRequest(AgentLoopInput input, List<Message> messages,
                                               ChatOptions options, int summarizeBudget) {
        CompactionOutcome outcome = null;
        for (LoopHook hook : hooks) {
            CompactionOutcome r = hook.beforeRequest(messages, input.chatModel(), options, summarizeBudget);
            if (r != null) {
                outcome = r;
            }
        }
        return outcome;
    }

    /** onRequestError 挂点链分发。 */
    private CompactionOutcome runOnRequestError(AgentLoopInput input, List<Message> messages,
                                                ChatOptions options, int summarizeBudget) {
        CompactionOutcome outcome = null;
        for (LoopHook hook : hooks) {
            CompactionOutcome r = hook.onRequestError(messages, input.chatModel(), options, summarizeBudget);
            if (r != null) {
                outcome = r;
            }
        }
        return outcome;
    }

    /** 终止前挂点链分发（REF-8.4）：携带消息历史 + 草稿结果，挂点可改写结果（write-only）。 */
    private AgentLoopResult terminate(AgentLoopInput input, List<Message> messages, AgentLoopResult result) {
        AgentLoopResult current = result;
        boolean internalLlm = input.accessType() == AgentAccessType.API_KEY_LLM;
        for (LoopHook hook : hooks) {
            try {
                AgentLoopResult revised = hook.beforeTerminate(
                        new AgentLoopTermination(input.chatModel(), messages, internalLlm, current,
                                input.maxIterations()));
                if (revised != null) {
                    current = revised;
                }
            } catch (Exception e) {
                log.warn("AgentLoop: beforeTerminate 挂点失败（write-only 不阻断）: err={}", e.getMessage());
            }
        }
        return current;
    }

    /**
     * 累加当轮 Token 用量（B3）：读取 {@code response.metadata.usage.totalTokens}；
     * metadata / usage / totalTokens 缺失时保持原累加值（best-effort，不阻断循环）。
     * 包私有 static，供同包 {@link FinalReportFenceHook} 收尾轮计数复用。
     */
    static Integer accumulateUsage(ChatResponse response, Integer accumulated) {
        Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
        if (usage == null || usage.getTotalTokens() == null) {
            return accumulated;
        }
        return (accumulated != null ? accumulated : 0) + usage.getTotalTokens();
    }

    /**
     * 构建工具调用选项：暴露已启用工具 schema，关闭 spring-ai 内部自动执行（由 ToolExecutor 接管）。
     */
    private DefaultToolCallingChatOptions buildOptions(AgentLoopInput input) {
        DefaultToolCallingChatOptions options = new DefaultToolCallingChatOptions();
        options.setToolCallbacks(input.enabledToolCallbacks());
        options.setInternalToolExecutionEnabled(false);
        return options;
    }

    /**
     * 执行单个工具调用并记录 TOOL_CALL 事件；记录失败仅告警（write-only，不阻断循环）。
     */
    private ToolExecutionResult executeToolSafely(AgentLoopInput input, String toolName, String arguments) {
        recordEvent(input, 3, AgentEventType.TOOL_CALL_STARTED,
                safeMap("tool", toolName, "arguments", arguments));
        ToolExecutionResult result = input.toolExecutor().execute(toolName, arguments);
        recordEvent(input, 4, AgentEventType.TOOL_CALL_COMPLETED,
                safeMap("tool", toolName, "success", result.success(),
                        "output", truncate(result.output())));
        return result;
    }

    /** 记录 REF-8.2.3 压缩审计事件（write-only；无压缩发生时不记）。 */
    private void recordCompaction(AgentLoopInput input, int step, CompactionOutcome outcome) {
        if (outcome == null) {
            return;
        }
        recordEvent(input, step, AgentEventType.CONTEXT_COMPACTED, safeMap(
                "trigger", outcome.trigger(),
                "prunedToolResults", outcome.prunedToolResults(),
                "shadowedMessages", outcome.shadowedMessages(),
                "estimatedTokensBefore", outcome.estimatedTokensBefore(),
                "estimatedTokensAfter", outcome.estimatedTokensAfter(),
                "summarized", outcome.summarized()));
    }

    private void recordEvent(AgentLoopInput input, int step, AgentEventType eventType, Map<String, Object> payload) {
        AgentEventRecorder recorder = input.eventRecorder();
        if (recorder == null) {
            return;
        }
        try {
            recorder.record(input.runId(), input.taskId(), input.subTaskId(), input.turn(),
                    step, eventType, input.agentId(), payload);
        } catch (Exception e) {
            log.warn("AgentLoop: 事件记录失败（write-only 不阻断）: type={}, err={}", eventType, e.getMessage());
        }
    }

    /**
     * 触发循环进度回调；回调缺失跳过、失败仅告警（write-only，不阻断循环）。
     */
    private void fireCheckpoint(AgentLoopInput input, LoopCheckpoint checkpoint) {
        LoopCheckpointListener listener = input.loopCheckpointListener();
        if (listener == null) {
            return;
        }
        try {
            listener.onCheckpoint(checkpoint);
        } catch (Exception e) {
            log.warn("AgentLoop: checkpoint 回调失败（write-only 不阻断）: iteration={}, err={}",
                    checkpoint.iteration(), e.getMessage());
        }
    }

    /** null 安全 payload 构造（Map.of 遇 null 会 NPE，循环内工具输出可能为 null）。 */
    private static Map<String, Object> safeMap(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= OUTPUT_TRUNCATE_CHARS ? value : value.substring(0, OUTPUT_TRUNCATE_CHARS);
    }
}
