package com.helloai.core.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.core.shared.handler.PgJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

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
     * 模型能力锁定技能（JSONB）：注册/编辑 Agent 时强制追加，不可取消。
     *
     * <p>内置模型默认 {@code ["thinking"]}；新模型未回填时取列默认值。</p>
     */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private List<String> capabilitySkills;

    /**
     * 模型可扩展技能白名单（JSONB）：注册时前端仅展示此集合，后端 validateAgentSkills 校验。
     *
     * <p>内置模型默认 {@code ["shell","code-review"]}；联网模型（kimi/qwen/minimax）回填 web-search。</p>
     */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private List<String> availableOptionalSkills;
}
