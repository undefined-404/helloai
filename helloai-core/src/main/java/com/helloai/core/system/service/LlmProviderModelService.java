package com.helloai.core.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.system.entity.LlmProviderModel;

import java.util.List;

/**
 * LLM Provider 模型业务服务（CRUD + 默认模型管理）。
 *
 * <p>v2.8 起 Service 层强制接口 + impl 拆分，本接口继承 IService 提供基础 CRUD 能力。</p>
 */
public interface LlmProviderModelService extends IService<LlmProviderModel> {

    /**
     * 批量保存 Provider 的模型配置（多选）。
     *
     * <p>逻辑：先删除该 Provider 的所有现有模型，再插入新选择的模型。
     * 必须保证至少有一个模型，且必须指定一个默认模型。</p>
     *
     * @param providerId   Provider ID
     * @param providerCode Provider Code（冗余）
     * @param modelNames   选中的模型名称列表
     * @param defaultModel 默认模型名称（必须在 modelNames 中）
     * @throws com.helloai.common.base.BizException 当模型列表为空或默认模型不在列表中时
     */
    void saveProviderModels(Long providerId, String providerCode, List<String> modelNames, String defaultModel);

    /**
     * 设置 Provider 的默认模型。
     *
     * @param providerId Provider ID
     * @param modelName  模型名称（必须已存在且启用）
     */
    void setDefaultModel(Long providerId, String modelName);

    /**
     * 添加单个模型到 Provider。
     */
    LlmProviderModel addModel(Long providerId, String providerCode, String modelName, boolean isDefault);

    /**
     * 删除模型（逻辑删除）。
     *
     * @param providerId Provider ID
     * @param modelName  模型名称
     * @throws com.helloai.common.base.BizException 当删除的是默认模型或最后一个模型时
     */
    void deleteModel(Long providerId, String modelName);

    /**
     * 启用/禁用模型。
     */
    void toggleModel(Long providerId, String modelName, boolean enabled);

    /**
     * 校验 Provider 是否有至少一个启用模型。
     */
    void validateProviderHasEnabledModels(Long providerId);

    /**
     * 物理删除指定 Provider 的全部模型记录（Provider 删除时级联清理）。
     *
     * <p>不走逻辑删除：Provider 已被删除时其模型配置应一并清除，
     * 避免残留记录让 isModelAvailable 误判已删 Provider 的模型可用。</p>
     *
     * @param providerId Provider ID
     */
    void deleteAllPhysicalByProviderId(Long providerId);
}
