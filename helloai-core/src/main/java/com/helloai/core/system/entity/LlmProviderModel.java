package com.helloai.core.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.core.shared.handler.PgJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * LLM Provider 模型配置。
 *
 * <p>每个 Provider 可配置多个可用模型，必须有一个默认模型。
 * 内置 Provider 模型列表固定，只可选不可改；自定义 Provider 支持任意模型名称。</p>
 *
 * <p>字段命名遵循 规范化规则：xxx_time / xxx_id / xxx_count。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "llm_provider_model", autoResultMap = true)
public class LlmProviderModel extends BaseEntity {

    /** 关联 llm_provider.id，外键级联删除。 */
    private Long providerId;

    /** 冗余 provider_code，便于按 code 查询（Agent 注册时校验）。 */
    private String providerCode;

    /** 模型名称，如 deepseek-v4-flash / kimi-k2.5 / qwen3.7-plus。 */
    private String modelName;

    /** 是否默认模型：1=是，0=否。每个 Provider 必须有一个默认模型（应用层校验）。 */
    private Integer isDefault;

    /** 启用/禁用：1=启用，0=禁用。禁用的模型不在 Agent 注册时展示。 */
    private Integer enabled;

    /** 列表排序（数值越小越靠前）。 */
    private Integer sortOrder;

    /**
     * 模型能力谓词（JSONB，`M-9` 起为技能判定的唯一依据）。
     *
     * <p>形态：{@code {"input":[模态],"output":[模态],"reasoning":bool,"toolCalling":bool,"webSearch":bool}}。
     * `V112` 已**摘掉列默认值**（结构性 fail-fast）——任何 {@code INSERT} 必须显式写谓词，
     * 否则 NOT NULL 违约；新增模型由 {@code LlmProviderModelServiceImpl} 写保守面。</p>
     *
     * <p>消费方经 {@link com.helloai.core.system.port.ModelCapabilities#fromRaw} 归一读取
     * （未知模态 token 原样保留、判定忽略、不抛错）。</p>
     */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private Map<String, Object> capabilities;
}
