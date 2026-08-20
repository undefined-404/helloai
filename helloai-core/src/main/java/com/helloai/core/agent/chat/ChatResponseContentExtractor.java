package com.helloai.core.agent.chat;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * ChatResponse 内容提取工具：分离正文与思考过程。
 *
 * <p>推理模型（如 Minimax ，Anthropic 协议）的响应含多个 content block：
 * thinking 块在前、text 正文在后。Spring AI AnthropicChatModel 把每个 block
 * 映射为一个 Generation（thinking 块的思考文本作为 AssistantMessage content，
 * properties 带 signature 标记）。若只取 {@code getResult()}（第一个 Generation）
 * 会拿到思考文本而丢弃正文——这正是自动核验 unparseable 的根因。</p>
 *
 * <p>本工具遍历全部 Generation：带 signature/data 标记的归入 thinking，
 * 其余 text 拼接为正文。OpenAI 协议系 provider（deepseek/moonshot/dashscope）
 * 单 Generation 且思考在 metadata（reasoningContent），行为不变。</p>
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

    /** Anthropic thinking 块标记：signature（thinking）或 data（redacted_thinking）。 */
    private static boolean isThinkingMessage(AssistantMessage message) {
        return message.getMetadata() != null
                && (message.getMetadata().containsKey("signature") || message.getMetadata().containsKey("data"));
    }
}
