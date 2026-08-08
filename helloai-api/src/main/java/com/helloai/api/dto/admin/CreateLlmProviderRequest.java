package com.helloai.api.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.Map;

/**
 * 新增 LLM Provider 请求（方案B）。
 *
 * <p>前端"添加供应商"对话框提交体。code 命名规则：全小写、字母数字中划线，长度 2-64。</p>
 */
@Data
public class CreateLlmProviderRequest {

    /** 唯一标识（如 deepseek / custom-gpt-4），全小写、字母数字中划线，长度 2-64。 */
    @NotBlank(message = "providerCode 必填")
    @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,63}", message = "providerCode 必须全小写、字母数字中划线，长度 2-64")
    private String providerCode;

    /** 显示名（如 "我的 OpenAI"）。 */
    @NotBlank(message = "providerName 必填")
    private String providerName;

    /** 协议类型：OPENAI_COMPATIBLE / ANTHROPIC_COMPATIBLE。 */
    @NotBlank(message = "protocolType 必填")
    @Pattern(regexp = "OPENAI_COMPATIBLE|ANTHROPIC_COMPATIBLE", message = "protocolType 仅支持 OPENAI_COMPATIBLE / ANTHROPIC_COMPATIBLE")
    private String protocolType;

    /** Base URL（必填）。 */
    @NotBlank(message = "baseUrl 必填")
    private String baseUrl;

    /** 默认模型（可选）。 */
    private String defaultModel;

    /** 启用状态（默认 1=启用）。 */
    private Integer enabled;

    /** 排序（默认 100）。 */
    private Integer sortOrder;

    /** 扩展配置 JSONB（可选）。 */
    private Map<String, Object> extraConfig;
}
