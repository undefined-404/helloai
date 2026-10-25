package com.helloai.core.agent.chat;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ChatResponse 内容提取工具：分离正文与思考过程。
 *
 * <p>推理模型（如 Minimax ，Anthropic 协议）的响应含多个 content block：
 * thinking 块在前、text 正文在后。Spring AI AnthropicChatModel 把每个 block
 * 映射为一个 Generation（thinking 块的思考文本作为 AssistantMessage content，
 * properties 带 signature 标记）。若只取 {@code getResult()}（第一个 Generation）
 * 会拿到思考文本而丢弃正文——这正是自动核验 unparseable 的根因。</p>
 *
 * <p>本工具遍历全部 Generation：带 signature/data 标记的（Anthropic）归入 thinking，
 * DeepSeek 的 {@code DeepSeekAssistantMessage.reasoningContent}（spring-ai-deepseek 将其
 * 映射为**独立字段**，不在 content / metadata）亦归入 thinking；其余 text 拼接为正文。</p>
 *
 * <p><b>工具调用同理走本类</b>（{@link #extractToolCalls}，2026-10-25 增补）：「正文被第 0 个
 * Generation 遮挡」与「工具调用被第 0 个 Generation 遮挡」是<b>同一个缺陷的两个面</b>，
 * 故两者共用同一处跨 Generation 归并，避免再次出现「改了一处、漏了另一处」。</p>
 */
public final class ChatResponseContentExtractor {

    private ChatResponseContentExtractor() {
    }

    /** 提取结果：text = 面向下游解析/展示的正文；thinking = 思考过程（无则空串）。 */
    public record ExtractedContent(String text, String thinking) {
    }

    public static ExtractedContent extract(ChatResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return new ExtractedContent("", "");
        }
        List<String> textParts = new ArrayList<>();
        List<String> thinkingParts = new ArrayList<>();
        for (Generation generation : response.getResults()) {
            AssistantMessage message = generation.getOutput();
            if (message == null) {
                continue;
            }
            // B（2026-10-05）DeepSeek 推理模型分支：思维链在**独立字段** reasoningContent
            // （既不在 getText()=content，也不在 Anthropic 的 signature/data metadata 里），
            // 归入 **thinking 通道**（下游 ExecutionResultHandler 落 conversation_message，
            // toolName=sub_task_execute_thinking，同一通道不新造）。
            // ★约束：仅可观测，**绝不并入正文 text**——避免污染交付物与核验；正文为空时
            //   仍由 RuntimeTurnExecutor 的空产出护栏判 FAILED（B 不改变 A 的结论）。
            if (message instanceof DeepSeekAssistantMessage deepSeek
                    && deepSeek.getReasoningContent() != null
                    && !deepSeek.getReasoningContent().isBlank()) {
                thinkingParts.add(deepSeek.getReasoningContent());
            }
            String content = message.getText();
            if (isThinkingMessage(message)) {
                if (content != null && !content.isBlank()) {
                    thinkingParts.add(content);
                }
            } else if (content != null && !content.isBlank()) {
                textParts.add(content);
            }
        }
        return new ExtractedContent(String.join("\n", textParts), String.join("\n", thinkingParts));
    }

    /**
     * 跨 Generation 汇总本轮模型声明的全部工具调用。
     *
     * <p><b>为什么必须跨 Generation</b>（2026-10-25 MiniMax「从不调用工具」事故）：
     * Spring AI {@code AnthropicChatModel.toChatResponse} 把文本块与 {@code tool_use} 块映射为
     * <b>两个独立的 Generation</b>——文本（或空占位）在索引 0，工具调用<b>追加在末尾</b>。
     * 而 {@link ChatResponse#getResult()} 恒取索引 0，故凡以 {@code getResult().getOutput()}
     * 取 {@code toolCalls} 的消费者，在 Anthropic 协议下<b>永远拿到空列表</b>，
     * 表现为「模型从不调用工具、只回一句开场白」（与模型能力无关：官方 Anthropic 回包同样如此，
     * 只是本项目此前只有 OpenAI 协议的 deepseek 能跑通工具链，缺陷被单协议掩盖）。
     *
     * <p>本方法按 Generation 顺序拼接、按 {@code id} 去重（同一 provider 回包内 id 唯一；
     * 缺失 id 的退化情形按「名字 + 参数」去重），空入参返回空列表（恒非 null）。</p>
     *
     * @param response 模型响应（可空）
     * @return 工具调用列表（顺序 = Generation 顺序），永不为 null
     */
    public static List<AssistantMessage.ToolCall> extractToolCalls(ChatResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Generation generation : response.getResults()) {
            AssistantMessage message = generation == null ? null : generation.getOutput();
            if (message == null || message.getToolCalls() == null || message.getToolCalls().isEmpty()) {
                continue;
            }
            for (AssistantMessage.ToolCall toolCall : message.getToolCalls()) {
                if (toolCall == null) {
                    continue;
                }
                String key = toolCall.id() != null && !toolCall.id().isBlank()
                        ? toolCall.id()
                        : toolCall.name() + "|" + toolCall.arguments();
                if (seen.add(key)) {
                    toolCalls.add(toolCall);
                }
            }
        }
        return toolCalls;
    }

    /** Anthropic thinking 块标记：signature（thinking）或 data（redacted_thinking）。 */
    private static boolean isThinkingMessage(AssistantMessage message) {
        return message.getMetadata() != null
                && (message.getMetadata().containsKey("signature") || message.getMetadata().containsKey("data"));
    }
}
