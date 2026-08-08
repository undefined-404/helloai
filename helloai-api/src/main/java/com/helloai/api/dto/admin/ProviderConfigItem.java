package com.helloai.api.dto.admin;

import lombok.Data;

/**
 * 管理端平台级 LLM Provider 配置项（不含 api-key 明文，仅脱敏串）。
 */
@Data
public class ProviderConfigItem {

    /** provider 名称（小写）。 */
    private String name;

    /** 当前生效的默认模型（sys_config > yml > Factory 内置默认）。 */
    private String defaultModel;

    /** 当前生效的 Base URL（sys_config > yml > Factory 内置默认）。 */
    private String baseUrl;

    /** 是否已配置平台级 API Key（vault 或 yml）。 */
    private boolean apiKeyConfigured;

    /** API Key 脱敏串（仅尾 4 位），未配置为 null。 */
    private String apiKeyMasked;

    /** 是否可用于手动注册平台内 LLM Agent（apiKeyConfigured && factorySupported）。 */
    private boolean available;

    /** 当前生效的 API Key 是否来自 vault（PLATFORM 级凭证）。 */
    private boolean apiKeyFromVault;
}
