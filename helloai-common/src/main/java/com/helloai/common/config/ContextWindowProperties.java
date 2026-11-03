package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上下文窗口与压缩配置（PA-12）。
 *
 * <p>前缀 {@code helloai.context-window}。token 估算用「字符数 / charsPerToken + 每条消息固定开销」，
 * 生产可按模型校准 {@code charsPerToken}；{@code contextSize} 为默认模型上下文窗口，
 * 会话级可在 {@code conversation_context_state.context_size} 覆盖。</p>
 */
@Data
@ConfigurationProperties(prefix = "helloai.context-window")
public class ContextWindowProperties {

    /** 全局开关：false 时不做自动压缩（手动 /compact 仍可显式触发）。 */
    private boolean enabled = true;

    /** 默认模型上下文窗口（token）。 */
    private int contextSize = 128000;

    /** 估算用的每 token 字符数（中文约 1.5~2，英文约 3~4）。 */
    private double charsPerToken = 1.6;

    /** 每条消息的固定模板开销（token）。 */
    private int messageOverhead = 4;

    /** 系统提示词估算占用（token）。 */
    private int systemPromptTokens = 800;

    /** 工具 schema 估算占用（token）。 */
    private int toolTokens = 0;

    /** 技能规范估算占用（token）。 */
    private int skillTokens = 0;

    /** 自动压缩阈值比例（总 token / contextSize）。 */
    private double triggerRatio = 0.80;

    /** 保留尾部比例（最近消息至少保留的 token 占比）。 */
    private double reserveRatio = 0.20;

    /** 摘要输入的字符上限（防止单次摘要调用过大）。 */
    private int summaryMaxChars = 60000;
}