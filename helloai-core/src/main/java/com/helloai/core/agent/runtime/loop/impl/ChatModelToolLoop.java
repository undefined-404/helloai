package com.helloai.core.agent.runtime.loop.impl;

import com.helloai.common.constant.AgentEventType;
import com.helloai.core.agent.chat.ChatResponseContentExtractor;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.runtime.loop.AgentLoop;
import com.helloai.core.agent.runtime.loop.AgentLoopInput;
import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import com.helloai.core.agent.tool.ToolExecutionResult;
import com.helloai.core.agent.tool.ToolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link AgentLoop} 真身——手动工具循环（spring-ai ChatModel 契约，provider 无关）。
 *
 * <p>核心机制：{@code DefaultToolCallingChatOptions.setInternalToolExecutionEnabled(false)}
 * 让模型只返回工具调用请求（不自动执行），由本类经 {@link ToolExecutor}（Phase 2 执行回路）
 * 执行并把 {@link ToolResponseMessage} 回喂给模型，循环至模型输出终态文本。</p>
 *
 * <p>事件：每次工具调用按 ADR-001 记 TOOL_CALL_STARTED（step=3）/ TOOL_CALL_COMPLETED（step=4），
 * payload 含工具名与入参/结果；write-only 纪律——记录失败仅告警不阻断循环。</p>
 *
 * <p>终止保证：maxIterations 硬上限（默认 5）防死循环；达到上限返回末轮正文的
 * MAX_ITERATIONS 结果。真实 provider 的工具调用行为需在 Runtime 真身接线时联调
 * （本构件以确定性 stub ChatModel 单测验证契约与循环逻辑）。</p>
 */
@Slf4j
@Component
public class ChatModelToolLoop implements AgentLoop {

    /** 事件 payload 中工具输出截断长度（防大输出刷爆事件表）。 */
    private static final int OUTPUT_TRUNCATE_CHARS = 500;

    @Override
    public AgentLoopResult run(AgentLoopInput input) {
        if (input.chatModel() == null || input.toolExecutor() == null) {
            return AgentLoopResult.error("agent loop requires chatModel and toolExecutor", 0, 0);
        }
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(input.systemPrompt() != null ? input.systemPrompt() : ""));
        messages.add(new UserMessage(input.userPrompt() != null ? input.userPrompt() : ""));

        int iterations = 0;
        int toolCallCount = 0;
        String lastText = "";

        for (int i = 0; i < input.maxIterations(); i++) {
            iterations = i + 1;
            ChatResponse response = input.chatModel().call(new Prompt(messages, buildOptions(input)));
            if (response == null) {
                return AgentLoopResult.error("chat model returned null response", iterations, toolCallCount);
            }
            ChatResponseContentExtractor.ExtractedContent extracted =
                    ChatResponseContentExtractor.extract(response);
            lastText = extracted.text();
            AssistantMessage assistant = response.getResult() != null ? response.getResult().getOutput() : null;
            if (assistant != null) {
                messages.add(assistant);
            }
            if (assistant == null || !assistant.hasToolCalls()) {
                return AgentLoopResult.stop(lastText,
                        extracted.thinking().isBlank() ? null : extracted.thinking(),
                        iterations, toolCallCount);
            }
            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
                if (toolCall == null) {
                    continue;
                }
                ToolExecutionResult result = executeToolSafely(input, toolCall.name(), toolCall.arguments());
                toolCallCount++;
                toolResponses.add(new ToolResponseMessage.ToolResponse(
                        toolCall.id(), toolCall.name(),
                        result.success() ? result.output() : "ERROR: " + result.errorMessage()));
            }
            if (toolResponses.isEmpty()) {
                // 模型发了空工具调用列表（异常响应）：防死循环，按终态返回
                return AgentLoopResult.stop(lastText,
                        extracted.thinking().isBlank() ? null : extracted.thinking(),
                        iterations, toolCallCount);
            }
            messages.add(ToolResponseMessage.builder().responses(toolResponses).build());
        }
        return AgentLoopResult.maxIterations(lastText, iterations, toolCallCount);
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
