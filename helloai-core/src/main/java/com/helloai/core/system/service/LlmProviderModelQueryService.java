package com.helloai.core.system.service;

import com.helloai.core.system.entity.LlmProviderModel;

import java.util.List;
import java.util.Optional;

/**
 * LLM Provider 模型查询服务。
 *
 * <p>提供 Provider 模型的只读查询能力，供 Agent 注册、Provider 管理等场景使用。</p>
 */
public interface LlmProviderModelQueryService {

    /**
     * 按 Provider ID 查询所有模型（含禁用）。
     */
    List<LlmProviderModel> listByProviderId(Long providerId);

    /**
     * 按 Provider ID 查询启用模型。
     */
    List<LlmProviderModel> listEnabledByProviderId(Long providerId);

    /**
     * 按 Provider Code 查询启用模型（Agent 注册时用）。
     */
    List<LlmProviderModel> listEnabledByProviderCode(String providerCode);

    /**
     * 查询 Provider 的默认模型。
     */
    Optional<LlmProviderModel> findDefaultByProviderId(Long providerId);

    /**
     * 查询 Provider 的默认模型名称。
     */
    Optional<String> findDefaultModelNameByProviderCode(String providerCode);

    /**
     * 校验模型是否可用（存在且启用）。
     */
    boolean isModelAvailable(String providerCode, String modelName);

    /**
     * 按 modelType（形如 providerCode:modelName）查询模型能力配置（V52 新增）。
     *
     * <p>返回实体携带 {@code capabilitySkills} / {@code availableOptionalSkills} 两列，
     * 供 Agent 技能推导与 skill-options 端点使用；模型不存在或已删除时返回 empty。</p>
     */
    Optional<LlmProviderModel> findCapabilityByModelType(String modelType);

    /**
     * 统计 Provider 的模型数量。
     */
    long countByProviderId(Long providerId);
}
