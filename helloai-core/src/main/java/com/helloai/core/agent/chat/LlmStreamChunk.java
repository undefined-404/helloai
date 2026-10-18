package com.helloai.core.agent.chat;

/**
 * LLM 流式输出的一块增量（正文 / 思考过程分离）。
 *
 * <p><b>为什么需要类型化</b>：同步链路用 {@link ChatResponseContentExtractor} 已经把
 * 「正文」与「思考过程」分开（DeepSeek 的 {@code reasoningContent}、Anthropic 的
 * thinking block 都归 thinking 通道）；而流式链路此前只取 {@code stream().content()}，
 * <b>思考过程被整体丢弃</b>——前端因此完全没有「思考中」的流式反馈，用户只能干等。</p>
 *
 * <p>本类型让流式与同步在**语义上对齐**：正文进正文，思考进思考，二者都可见、
 * 但不混流（落库与后续解析只消费 {@link Type#CONTENT}）。</p>
 *
 * @param type 块类型（正文 / 思考）
 * @param text 增量文本（非 null；空块在源头已被过滤）
 */
public record LlmStreamChunk(Type type, String text) {

    /** 增量块类型。 */
    public enum Type {
        /** 主回复正文增量（落库、解析、最终展示都只认这个）。 */
        CONTENT,
        /** 思考过程增量（推理模型；仅用于前端实时展示，绝不并入正文与落库内容）。 */
        REASONING
    }

    public LlmStreamChunk {
        text = text == null ? "" : text;
    }

    public static LlmStreamChunk content(String text) {
        return new LlmStreamChunk(Type.CONTENT, text);
    }

    public static LlmStreamChunk reasoning(String text) {
        return new LlmStreamChunk(Type.REASONING, text);
    }

    public boolean isReasoning() {
        return type == Type.REASONING;
    }
}
