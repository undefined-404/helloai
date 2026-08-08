package com.helloai.api.dto.admin;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.Map;

/**
 * 修改 LLM Provider 请求（方案B）。
 *
 * <p>局部更新：所有字段可选，null 字段不更新。注意：内置 Provider 不可修改 providerCode（服务端拒绝）。</p>
 */
@Data
public class UpdateLlmProviderRequest {

    /** 显示名（可选）。 */
    private String providerName;

    /** 协议类型（可选）。 */
    @Pattern(regexp = "OPENAI_COMPATIBLE|ANTHROPIC_COMPATIBLE", message = "protocolType 仅支持 OPENAI_COMPATIBLE / ANTHROPIC_COMPATIBLE")
    private String protocolType;

    /** Base URL（可选）。 */
    private String baseUrl;

    /** 默认模型（可选）。 */
    private String defaultModel;

    /** 启用状态（可选）。 */
    private Integer enabled;

    /** 排序（可选）。 */
    private Integer sortOrder;

    /** 扩展配置 JSONB（可选）。 */
    private Map<String, Object> extraConfig;
}
