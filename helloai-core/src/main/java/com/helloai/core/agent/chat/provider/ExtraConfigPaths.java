package com.helloai.core.agent.chat.provider;

import java.util.Map;

/**
 * {@code llm_provider.extra_config} 中「端点路径覆盖项」的唯一读取器（M-5）。
 *
 * <p><b>为什么单独一个类</b>：{@code completionsPath} / {@code messagesPath} 两个键被两个协议
 * 工厂读取，键名与「空白即视为未配置」的归一化语义必须**同源**；散成字面量会各自漂移
 * （对齐「复用同一匹配器防漂移」判据）。</p>
 *
 * <p><b>缺省语义</b>：未配置 / 空串 / 全空白 ⇒ 返回 {@code null}，调用方**不得**写入 Api Builder
 * （交由 Spring AI 使用其默认路径 {@code /v1/chat/completions} 与 {@code /v1/messages}），
 * 从而与 M-5 之前的运行时行为**逐字一致**。</p>
 *
 * <p><b>列契约</b>：{@code V46:27,63}「扩展配置（completionsPath / messagesPath）」；
 * 本类是该字段的**首个读取方**（此前全仓 0 处消费，属「只写不读」）。</p>
 */
final class ExtraConfigPaths {

    /** OpenAI 兼容端点路径覆盖键（{@code OpenAiApi.Builder#completionsPath}）。 */
    static final String COMPLETIONS_PATH = "completionsPath";

    /** Anthropic 兼容端点路径覆盖键（{@code AnthropicApi.Builder#completionsPath}）。 */
    static final String MESSAGES_PATH = "messagesPath";

    private ExtraConfigPaths() {
    }

    /**
     * 读取端点路径覆盖值。
     *
     * @param extraConfig 快照中的扩展配置（允许 {@code null}）
     * @param key         {@link #COMPLETIONS_PATH} 或 {@link #MESSAGES_PATH}
     * @return 去首尾空白后的路径；未配置 / 空白 ⇒ {@code null}（调用方跳过覆盖）
     */
    static String path(Map<String, Object> extraConfig, String key) {
        if (extraConfig == null || key == null) {
            return null;
        }
        Object raw = extraConfig.get(key);
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }
}
