package com.helloai.core.system.service;

import com.helloai.core.system.entity.LlmProviderModel;
import com.helloai.core.system.port.LlmProviderModelProfile;

import java.util.List;

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
     * 查询 Provider 的默认模型（不存在或已删除返回 null）。
     */
    LlmProviderModel findDefaultByProviderId(Long providerId);

    /**
     * 查询 Provider 的默认模型名称（不存在或已删除返回 null）。
     */
    String findDefaultModelNameByProviderCode(String providerCode);

    /**
     * 校验模型是否可用（存在且启用）。
     */
    boolean isModelAvailable(String providerCode, String modelName);

    /**
     * 按 modelType（形如 providerCode:modelName）查询模型能力配置。
     *
     * <p>返回实体（`M-9` 起）携带能力谓词列 {@code capabilities}；模型不存在或已删除时返回 null。</p>
     */
    LlmProviderModel findCapabilityByModelType(String modelType);

    /**
     * 按 modelType 查询模型能力配置的【只读快照】（RM5 批 3；`M-9` 换内容）。
     *
     * <p>语义与 {@link #findCapabilityByModelType} 逐字一致（模型不存在或已删除返回 null），
     * 但只暴露能力谓词 {@link com.helloai.core.system.port.ModelCapabilities}，
     * 供 agent 域技能推导使用，避免其 import {@code system.entity.LlmProviderModel}。</p>
     */
    LlmProviderModelProfile findCapabilityProfileByModelType(String modelType);

    /**
     * 统计 Provider 的模型数量。
     */
    long countByProviderId(Long providerId);
}
