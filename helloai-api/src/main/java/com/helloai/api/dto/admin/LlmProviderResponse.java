package com.helloai.api.dto.admin;

import lombok.Data;

import java.util.Map;

/**
 * 管理端 LLM Provider 列表 / 详情响应（方案B）。
 *
 * <p>适配 Codex++ 风格"可动态添加供应商"UI：含 provider_code / provider_name / 协议类型 / 默认模型 /
 * 启用状态 / 内置标识 / 排序 / 扩展配置 / API Key 脱敏信息。</p>
 */
@Data
public class LlmProviderResponse {

    /** 数据库主键。 */
    private Long id;

    /** 唯一标识（如 deepseek / moonshot / custom-gpt-4）。 */
    private String providerCode;

    /** 显示名（如 "DeepSeek"、"我的 OpenAI"）。 */
    private String providerName;

    /** 协议类型：OPENAI_COMPATIBLE / ANTHROPIC_COMPATIBLE。 */
    private String protocolType;

    /** 当前生效 Base URL（DB > sys_config > yml）。 */
    private String baseUrl;

    /** 当前生效默认模型（DB > sys_config > yml）。 */
    private String defaultModel;

    /** 启用 / 禁用（1=启用，0=禁用）。 */
    private Integer enabled;

    /** 是否内置（1=不可删除）。 */
    private Integer builtin;

    /** 列表排序。 */
    private Integer sortOrder;

    /** 计费类型：API_KEY=按量付费（默认）；TOKEN_PLAN / CODING_PLAN 预留。 */
    private String billingType;

    /** 扩展配置 JSONB。 */
    private Map<String, Object> extraConfig;

    /** 是否已配置 API Key（vault 或 yml）。 */
    private Boolean apiKeyConfigured;

    /** API Key 脱敏串（仅尾 4 位）。 */
    private String apiKeyMasked;

    /** 当前 API Key 是否来自 vault。 */
    private Boolean apiKeyFromVault;
}
