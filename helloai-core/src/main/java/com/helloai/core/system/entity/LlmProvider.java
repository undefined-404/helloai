package com.helloai.core.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * LLM Provider 配置（动态化方案B主表）。
 *
 * <p>替代原 application.yml 中 helloai.providers.&lt;name&gt;.* 硬编码段与
 * sys_config["llm.provider.&lt;name&gt;.base-url"] 的散落 key。密钥仍走
 * credential_vault（PLATFORM 级），本表只存 Provider 维度的协议与端点配置。</p>
 *
 * <p>字段命名遵循 规范化规则：xxx_time / xxx_id / xxx_count。</p>
 *
 * <p>协议类型约束：本轮只支持 {@code OPENAI_COMPATIBLE} / {@code ANTHROPIC_COMPATIBLE}，
 * 校验在 {@link com.helloai.core.system.service.LlmProviderService} 中执行。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "llm_provider", autoResultMap = true)
public class LlmProvider extends BaseEntity {

    /** 唯一标识（如 deepseek / moonshot / custom-gpt-4），全小写、不可重复。 */
    private String providerCode;

    /** 显示名（如 "DeepSeek"、"我的 OpenAI"）。 */
    private String providerName;

    /** 协议类型：OPENAI_COMPATIBLE / ANTHROPIC_COMPATIBLE。 */
    private String protocolType;

    /** API Base URL（OpenAiApi 在其后拼接 /v1/chat/completions）。 */
    private String baseUrl;

    /** 默认模型，可空——空时由调用方传入或 Factory 兜底。 */
    private String defaultModel;

    /** 启用 / 禁用（1=启用，0=禁用）。 */
    private Integer enabled;

    /** 是否内置（1=不可删除、不可改 code）。 */
    private Integer builtin;

    /** 列表排序（数值越小越靠前）。 */
    private Integer sortOrder;

    /** 扩展配置（如 openai 的 completionsPath、anthropic 的 messagesPath）。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> extraConfig;
}
