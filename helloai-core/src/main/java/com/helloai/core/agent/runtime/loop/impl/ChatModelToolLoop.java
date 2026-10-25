package com.helloai.core.agent.runtime.loop.impl;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentEventType;
import com.helloai.core.agent.chat.ChatResponseContentExtractor;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.runtime.loop.AgentLoop;
import com.helloai.core.agent.runtime.loop.AgentLoopInput;
import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import com.helloai.core.agent.runtime.loop.AgentUsage;
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
import java.util.Locale;
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
 *
 * <p><b>★截断终态（REF-8.7）</b>：每轮读响应声明的结束原因（{@code generation.metadata.finishReason}），
 * 命中 token 上限 token 集即置 **sticky** 标志 —— 该 turn 的**全部出口**（`STOP` 与
 * `MAX_ITERATIONS`）都改以 {@link AgentLoopResult#maxTokens} 返回，避免「截断被静默当成功」。
 * sticky 由「**出口前**把草稿定为 MAX_TOKENS」实现 ⇒ {@code beforeTerminate} 挂点收到的是
 * **非成功**草稿（现有挂点据此跳过改写），故不会被下调。**未知结束原因一律不判截断**（fail-open：
 * 不因本判据新增拒绝面，违 §10.8 红线③）。</p>
 */
@Slf4j
@Component
public class ChatModelToolLoop implements AgentLoop {

    /** 事件 payload 中工具输出截断长度（防大输出刷爆事件表）。 */
    private static final int OUTPUT_TRUNCATE_CHARS = 500;

    /**
     * 表示「输出被 token 上限截断」的结束原因取值集（REF-8.7）。
     *
     * <p>取值随 provider 而异 —— 实测：OpenAI / DeepSeek 适配器为枚举名 {@code LENGTH}
     * （`OpenAiApi$ChatCompletionFinishReason` / `DeepSeekApi$ChatCompletionFinishReason`）；
     * Anthropic 兼容面直接用协议原值 {@code max_tokens}。比较前统一 {@code trim + toLowerCase}，
     * 故大小写差异不影响判定。**未知取值不判截断**（降级回改造前语义）。</p>
     */
    private static final Set<String> TRUNCATION_FINISH_REASONS = Set.of(
            "length", "max_tokens", "max_tokens_reached", "max_output_tokens", "max_completion_tokens");

    /** 缓存读分量在 provider 私有 usage 中的候选键（REF-8.8；取不到即 null，降级不报错）。 */
    private static final String[] CACHE_READ_KEYS =
            {"cached_tokens", "cache_read_tokens", "cache_read_input_tokens"};

    /** 缓存写分量候选键（同上）。 */
    private static final String[] CACHE_WRITE_KEYS =
            {"cache_write_tokens", "cache_creation_input_tokens"};

    /** 推理分量候选键（同上）。 */
    private static final String[] REASONING_KEYS =
            {"reasoning_tokens"};

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
        // REF-8.8：墙钟起点（耗时含轮内全部动作 + 终止挂点如收尾轮）
        long startedNanos = System.nanoTime();
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
        // REF-8.7 sticky：任一步命中 token 上限即置位，此后本 turn 终态不得下调为 STOP（DSH 同口径）
        boolean truncated = false;
        // REF-8.8 用量累计（互斥分量）：provider 未给的分量保持 null（降级不报错，不新增拒绝面）
        AgentUsage runningUsage = AgentUsage.empty();

        int overflowRetries = 0;
        int summarizeBudget = compactor.maxSummarizationsPerRun();
        // ★2026-10-25（MiniMax 竞品分析 MAX_ITERATIONS 事故）：轮次护栏——最后 2 轮强制收尾。
        // 根因：模型把 20 轮全花在沙箱 curl 抓取上，正文零产出。提示词侧已加「调研抓取最多 6 轮」，
        // 但模型可能不遵从。故在循环层加硬护栏：第 maxIterations-1 轮起禁用工具调用，强制模型输出正文。
        int forceFinalizeFromIteration = Math.max(1, input.maxIterations() - 1);
        for (int i = 0; i < input.maxIterations(); i++) {
            iterations = i + 1;
            DefaultToolCallingChatOptions options = buildOptions(input);
            // 轮次护栏：最后 2 轮禁用工具调用（强制模型输出正文，不再抓取）
            if (iterations >= forceFinalizeFromIteration) {
                options.setToolCallbacks(List.of());
                log.info("ChatModelToolLoop: 轮次护栏触发（第 {}/{} 轮），禁用工具调用强制收尾",
                        iterations, input.maxIterations());
            }
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
            // REF-8.8：累计互斥分量（输入 / 输出 / 缓存读 / 缓存写 / 推理），与总数并行不互替
            runningUsage = accumulateUsage(response, runningUsage);
            // REF-8.7：读本轮声明的结束原因，命中截断 token 集即置 sticky 标志（只置不撤）
            if (isTruncated(finishReasonOf(response))) {
                truncated = true;
            }
            ChatResponseContentExtractor.ExtractedContent extracted =
                    ChatResponseContentExtractor.extract(response);
            lastText = extracted.text();
            // 2026-10-25（MiniMax「从不调用工具」事故）：Anthropic 协议下 AnthropicChatModel 把
            // 文本块与 tool_use 块映射为**两个独立 Generation**（正文在索引 0、工具调用追加在末尾），
            // 而 getResult() 恒取索引 0 ⇒ 只读 getResult() 时工具调用被系统性遮蔽，
            // 循环误判「模型没要工具」而按 STOP 收尾。故一律跨 Generation 汇总。
            List<AssistantMessage.ToolCall> toolCalls = ChatResponseContentExtractor.extractToolCalls(response);
            AssistantMessage assistant = assistantForHistory(response, extracted, toolCalls);
            if (assistant != null) {
                messages.add(assistant);
            }
            if (toolCalls.isEmpty()) {
                return terminate(input, messages, stopDraft(truncated, lastText,
                        extracted.thinking().isBlank() ? null : extracted.thinking(),
                        iterations, toolCallCount, totalTokens), runningUsage, startedNanos);
            }
            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            for (AssistantMessage.ToolCall toolCall : toolCalls) {
                if (toolCall == null) {
                    continue;
                }
                ToolExecutionResult result = executeToolSafely(input, toolCall.id(),
                        toolCall.name(), toolCall.arguments());
                toolCallCount++;
                executedToolNames.add(toolCall.name());
                toolResponses.add(new ToolResponseMessage.ToolResponse(
                        toolCall.id(), toolCall.name(),
                        result.success() ? result.output() : "ERROR: " + result.errorMessage()));
            }
            if (toolResponses.isEmpty()) {
                // 模型发了空工具调用列表（异常响应）：防死循环，按终态返回
                return terminate(input, messages, stopDraft(truncated, lastText,
                        extracted.thinking().isBlank() ? null : extracted.thinking(),
                        iterations, toolCallCount, totalTokens), runningUsage, startedNanos);
            }
            messages.add(ToolResponseMessage.builder().responses(toolResponses).build());
            // 循环进度检查点（P0-C checkpoint）：本轮 LLM + 工具均已完成、下一轮未开始；
            // write-only 纪律——落库失败仅告警，不阻断循环（V66 边界：只落进度事实）
            fireCheckpoint(input, new LoopCheckpoint(
                    i + 1, toolCallCount, messages.size(), new ArrayList<>(executedToolNames)));
        }
        // REF-8.7：截断优先于轮数耗尽（sticky）——二者皆为「未完成」，截断是更具体的终态；
        // 置位后本出口亦不得回落为 MAX_ITERATIONS。
        AgentLoopResult draft = truncated
                ? AgentLoopResult.maxTokens(lastText, null, iterations, toolCallCount, totalTokens)
                : AgentLoopResult.maxIterations(lastText, iterations, toolCallCount, totalTokens);
        return terminate(input, messages, draft, runningUsage, startedNanos);
    }

    /**
     * 终态草稿（REF-8.7）：自然终止出口按 sticky 标志在 {@code STOP} 与 {@code MAX_TOKENS} 间选择。
     *
     * <p>把选择放在<b>出口</b>（进入 {@code beforeTerminate} 挂点<b>之前</b>）而非挂点之后 ——
     * 使挂点收到的是<b>非成功</b>草稿（现有 {@code FinalReportFenceHook} 据此跳过收尾轮改写），
     * 从而「sticky」不依赖挂点自觉，也不会被挂点下调。</p>
     */
    private static AgentLoopResult stopDraft(boolean truncated, String text, String thinking,
                                             int iterations, int toolCallCount, Integer totalTokens) {
        return truncated
                ? AgentLoopResult.maxTokens(text, thinking, iterations, toolCallCount, totalTokens)
                : AgentLoopResult.stop(text, thinking, iterations, toolCallCount, totalTokens);
    }

    /** 读本轮响应声明的结束原因（result / metadata 缺失或 provider 未给 ⇒ null）。 */
    private static String finishReasonOf(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return null;
        }
        var metadata = response.getResult().getMetadata();
        return metadata != null ? metadata.getFinishReason() : null;
    }

    /**
     * 组装本轮要写回会话历史的 AssistantMessage。
     *
     * <p><b>为什么不能直接用 {@code getResult().getOutput()}</b>（2026-10-25 MiniMax 事故）：
     * Anthropic 协议下文本块与 {@code tool_use} 块是两个独立 Generation，直接写回第 0 个会同时
     * 造成两个后果——① 本轮判定拿不到工具调用（见 {@link ChatResponseContentExtractor#extractToolCalls}）；
     * ② 历史里没有对应的 {@code tool_use} 块，下一轮追加的 {@code tool_result} 变成「无主」，
     * provider 会因「tool_result 前无对应 tool_use」直接拒答。</p>
     *
     * <p>故：汇总出的工具调用不在原消息里时<b>重新合成</b>「正文 + 全部工具调用」；
     * 无工具调用、或原消息已自带工具调用（OpenAI / DeepSeek 单 Generation 形态）时<b>原样保留</b>
     * —— 保住 {@code DeepSeekAssistantMessage} 子类与 metadata，对既有链路零回归。</p>
     */
    private static AssistantMessage assistantForHistory(
            ChatResponse response,
            ChatResponseContentExtractor.ExtractedContent extracted,
            List<AssistantMessage.ToolCall> toolCalls) {
        AssistantMessage raw = response.getResult() != null ? response.getResult().getOutput() : null;
        if (toolCalls.isEmpty() || (raw != null && raw.hasToolCalls())) {
            return raw;
        }
        return AssistantMessage.builder()
                .content(extracted.text() != null ? extracted.text() : "")
                .toolCalls(toolCalls)
                .build();
    }

    /**
     * 该结束原因是否表示「输出被 token 上限截断」（REF-8.7）。取值集与容错口径见
     * {@link #TRUNCATION_FINISH_REASONS}；<b>未知取值一律不判截断</b>（降级回改造前语义）。
     */
    static boolean isTruncated(String finishReason) {
        if (finishReason == null) {
            return false;
        }
        return TRUNCATION_FINISH_REASONS.contains(finishReason.trim().toLowerCase(Locale.ROOT));
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
    private AgentLoopResult terminate(AgentLoopInput input, List<Message> messages, AgentLoopResult result,
                                      AgentUsage usage, long startedNanos) {
        AgentLoopResult current = result;
        boolean internalLlm = input.accessType() == AgentAccessType.API_KEY_LLM;
        for (LoopHook hook : hooks) {
            try {
                AgentLoopResult revised = hook.beforeTerminate(
                        new AgentLoopTermination(input.chatModel(), messages, internalLlm, current,
                                input.maxIterations(), input.scene()));
                if (revised != null) {
                    current = revised;
                }
            } catch (Exception e) {
                log.warn("AgentLoop: beforeTerminate 挂点失败（write-only 不阻断）: err={}", e.getMessage());
            }
        }
        // REF-8.8：用量在挂点改写「之后」统一装配 —— 挂点可改终态 / 正文（如收尾轮），
        // 但不会把用量丢掉（正交装配）。耗时按墙钟算，含挂点（收尾轮）耗时。
        long latencyMillis = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        AgentUsage effective = usage != null ? usage : AgentUsage.empty();
        return current.withUsage(effective.withLatency(latencyMillis));
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
     * 累加当轮用量的**互斥分量**（REF-8.8）：输入 / 输出由 spring-ai 通用契约稳定提供；
     * 缓存读 / 缓存写 / 推理属 provider 私有（见 {@link #nativeToken}），取不到即为 null。
     * provider 未给 usage ⇒ 原样返回（best-effort，不阻断循环，不新增拒绝面）。
     */
    static AgentUsage accumulateUsage(ChatResponse response, AgentUsage accumulated) {
        AgentUsage base = accumulated != null ? accumulated : AgentUsage.empty();
        Usage usage = response != null && response.getMetadata() != null
                ? response.getMetadata().getUsage() : null;
        if (usage == null) {
            return base;
        }
        return base.plus(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens(),
                nativeToken(usage, CACHE_READ_KEYS), nativeToken(usage, CACHE_WRITE_KEYS),
                nativeToken(usage, REASONING_KEYS));
    }

    /**
     * 从 provider 私有 {@code nativeUsage} 中按候选键取整型分量（best-effort）。
     *
     * <p>{@code nativeUsage} 的实际类型随 provider 而异（多为 provider 自有类型而非 Map）⇒
     * <b>只有</b>当它是 {@code Map} 时才读取；否则返回 null（降级，不报错、不猜测）。</p>
     */
    private static Integer nativeToken(Usage usage, String... candidateKeys) {
        Object nativeUsage = usage.getNativeUsage();
        if (!(nativeUsage instanceof Map<?, ?> map)) {
            return null;
        }
        for (String key : candidateKeys) {
            Object value = map.get(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
        }
        return null;
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
     *
     * <p><b>为什么带 callId</b>（2026-10-18）：模型返回的 {@code ToolCall.id} 是与 provider
     * 调用记录一一对应的唯一键。此前只记工具名，导致「调用」与「返回」只能靠
     * 「同名 + 时间相邻」配对——一旦事件丢一条或将来改为并行执行，配对即失效；
     * 排查悬挂调用（只有 started 没有 completed）时也无法确定是哪一次。
     * 该 id 本来就在手边（同一循环里已用于 {@code ToolResponseMessage.ToolResponse}），
     * 只是没落进事件 payload。</p>
     */
    private ToolExecutionResult executeToolSafely(AgentLoopInput input, String callId,
                                                  String toolName, String arguments) {
        recordEvent(input, 3, AgentEventType.TOOL_CALL_STARTED,
                safeMap("tool", toolName, "arguments", arguments, "callId", callId));
        ToolExecutionResult result = input.toolExecutor().execute(toolName, arguments);
        recordEvent(input, 4, AgentEventType.TOOL_CALL_COMPLETED,
                safeMap("tool", toolName, "success", result.success(),
                        "output", truncate(result.output()), "callId", callId));
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
