package com.helloai.core.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.core.system.entity.LlmProviderModel;
import com.helloai.core.system.port.LlmProviderModelProfile;
import com.helloai.core.system.port.ModelCapabilities;
import com.helloai.core.system.mapper.LlmProviderModelMapper;
import com.helloai.core.system.service.LlmProviderModelQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * LLM Provider 模型查询服务实现。
 *
 * <p>提供 Provider 模型的只读查询能力，所有列表查询方法保证返回空列表而非 null。</p>
 */
@Service
@RequiredArgsConstructor
public class LlmProviderModelQueryServiceImpl implements LlmProviderModelQueryService {

    private final LlmProviderModelMapper llmProviderModelMapper;

    @Override
    public List<LlmProviderModel> listByProviderId(Long providerId) {
        if (providerId == null) {
            return Collections.emptyList();
        }
        List<LlmProviderModel> list = llmProviderModelMapper.selectList(
                new LambdaQueryWrapper<LlmProviderModel>()
                        .eq(LlmProviderModel::getProviderId, providerId)
                        .eq(LlmProviderModel::getDeleted, 0)
                        .orderByAsc(LlmProviderModel::getSortOrder)
                        .orderByAsc(LlmProviderModel::getModelName)
        );
        return list != null ? list : Collections.emptyList();
    }

    @Override
    public List<LlmProviderModel> listEnabledByProviderId(Long providerId) {
        if (providerId == null) {
            return Collections.emptyList();
        }
        List<LlmProviderModel> list = llmProviderModelMapper.selectList(
                new LambdaQueryWrapper<LlmProviderModel>()
                        .eq(LlmProviderModel::getProviderId, providerId)
                        .eq(LlmProviderModel::getEnabled, 1)
                        .eq(LlmProviderModel::getDeleted, 0)
                        .orderByAsc(LlmProviderModel::getSortOrder)
                        .orderByAsc(LlmProviderModel::getModelName)
        );
        return list != null ? list : Collections.emptyList();
    }

    @Override
    public List<LlmProviderModel> listEnabledByProviderCode(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            return Collections.emptyList();
        }
        List<LlmProviderModel> list = llmProviderModelMapper.selectList(
                new LambdaQueryWrapper<LlmProviderModel>()
                        .eq(LlmProviderModel::getProviderCode, providerCode.toLowerCase().trim())
                        .eq(LlmProviderModel::getEnabled, 1)
                        .eq(LlmProviderModel::getDeleted, 0)
                        .orderByAsc(LlmProviderModel::getSortOrder)
                        .orderByAsc(LlmProviderModel::getModelName)
        );
        return list != null ? list : Collections.emptyList();
    }

    @Override
    public LlmProviderModel findDefaultByProviderId(Long providerId) {
        if (providerId == null) {
            return null;
        }
        LlmProviderModel model = llmProviderModelMapper.selectOne(
                new LambdaQueryWrapper<LlmProviderModel>()
                        .eq(LlmProviderModel::getProviderId, providerId)
                        .eq(LlmProviderModel::getIsDefault, 1)
                        .eq(LlmProviderModel::getEnabled, 1)
                        .eq(LlmProviderModel::getDeleted, 0)
                        .last("LIMIT 1")
        );
        return model;
    }

    @Override
    public String findDefaultModelNameByProviderCode(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            return null;
        }
        LlmProviderModel model = llmProviderModelMapper.selectOne(
                new LambdaQueryWrapper<LlmProviderModel>()
                        .eq(LlmProviderModel::getProviderCode, providerCode.toLowerCase().trim())
                        .eq(LlmProviderModel::getIsDefault, 1)
                        .eq(LlmProviderModel::getEnabled, 1)
                        .eq(LlmProviderModel::getDeleted, 0)
                        .last("LIMIT 1")
        );
        return model != null ? model.getModelName() : null;
    }

    @Override
    public LlmProviderModel findCapabilityByModelType(String modelType) {
        if (modelType == null || modelType.isBlank()) {
            return null;
        }
        String[] parts = modelType.split(":", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return null;
        }
        LlmProviderModel model = llmProviderModelMapper.selectOne(
                new LambdaQueryWrapper<LlmProviderModel>()
                        .eq(LlmProviderModel::getProviderCode, parts[0].toLowerCase().trim())
                        .eq(LlmProviderModel::getModelName, parts[1].trim())
                        .eq(LlmProviderModel::getDeleted, 0)
                        .last("LIMIT 1")
        );
        return model;
    }

    @Override
    public LlmProviderModelProfile findCapabilityProfileByModelType(String modelType) {
        LlmProviderModel model = findCapabilityByModelType(modelType);
        // 语义与 findCapabilityByModelType 逐字一致（不存在/已删 ⇒ null）；仅换内容为谓词快照。
        // 谓词解析归一（缺键 / 类型不符 / 空数组 ⇒ 保守面；未知模态 token 原样保留）收口于
        // ModelCapabilities#fromRaw（M-9：V52 两列退役，能力判据切到 capabilities 谓词）。
        return model == null ? null
                : new LlmProviderModelProfile(ModelCapabilities.fromRaw(model.getCapabilities()));
    }

    @Override
    public boolean isModelAvailable(String providerCode, String modelName) {
        if (providerCode == null || providerCode.isBlank() || modelName == null || modelName.isBlank()) {
            return false;
        }
        Long count = llmProviderModelMapper.selectCount(
                new LambdaQueryWrapper<LlmProviderModel>()
                        .eq(LlmProviderModel::getProviderCode, providerCode.toLowerCase().trim())
                        .eq(LlmProviderModel::getModelName, modelName.trim())
                        .eq(LlmProviderModel::getEnabled, 1)
                        .eq(LlmProviderModel::getDeleted, 0)
        );
        return count != null && count > 0;
    }

    @Override
    public long countByProviderId(Long providerId) {
        if (providerId == null) {
            return 0;
        }
        Long count = llmProviderModelMapper.selectCount(
                new LambdaQueryWrapper<LlmProviderModel>()
                        .eq(LlmProviderModel::getProviderId, providerId)
                        .eq(LlmProviderModel::getDeleted, 0)
        );
        return count != null ? count : 0;
    }
}
