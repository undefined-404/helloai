package com.helloai.api.dto.admin;

import lombok.Data;

/**
 * LLM Provider 模型响应 DTO。
 *
 * <p>用于返回 Provider 关联的模型列表，包含模型名称、是否默认、是否启用等信息。</p>
 */
@Data
public class LlmProviderModelResponse {

    /**
     * 模型 ID
     */
    private Long id;

    /**
     * 模型名称（如 deepseek-v4-flash）
     */
    private String modelName;

    /**
     * 是否默认模型（1=是，0=否）
     */
    private Integer isDefault;

    /**
     * 是否启用（1=启用，0=禁用）
     */
    private Integer enabled;

    /**
     * 排序号（越小越靠前）
     */
    private Integer sortOrder;
}
