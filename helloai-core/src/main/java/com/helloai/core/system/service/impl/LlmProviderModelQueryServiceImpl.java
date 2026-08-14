package com.helloai.core.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.core.system.entity.LlmProviderModel;
import com.helloai.core.system.mapper.LlmProviderModelMapper;
import com.helloai.core.system.service.LlmProviderModelQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

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
    public Optional<LlmProviderModel> findDefaultByProviderId(Long providerId) {
        if (providerId == null) {
            return Optional.empty();
        }
        LlmProviderModel model = llmProviderModelMapper.selectOne(
                new LambdaQueryWrapper<LlmProviderModel>()
                        .eq(LlmProviderModel::getProviderId, providerId)
                        .eq(LlmProviderModel::getIsDefault, 1)
                        .eq(LlmProviderModel::getEnabled, 1)
                        .eq(LlmProviderModel::getDeleted, 0)
                        .last("LIMIT 1")
        );
        return Optional.ofNullable(model);
    }

    @Override
    public Optional<String> findDefaultModelNameByProviderCode(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            return Optional.empty();
        }
        LlmProviderModel model = llmProviderModelMapper.selectOne(
                new LambdaQueryWrapper<LlmProviderModel>()
                        .eq(LlmProviderModel::getProviderCode, providerCode.toLowerCase().trim())
                        .eq(LlmProviderModel::getIsDefault, 1)
                        .eq(LlmProviderModel::getEnabled, 1)
                        .eq(LlmProviderModel::getDeleted, 0)
                        .last("LIMIT 1")
        );
        return Optional.ofNullable(model != null ? model.getModelName() : null);
    }

    @Override
    public Optional<LlmProviderModel> findCapabilityByModelType(String modelType) {
        if (modelType == null || modelType.isBlank()) {
            return Optional.empty();
        }
        String[] parts = modelType.split(":", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return Optional.empty();
        }
        LlmProviderModel model = llmProviderModelMapper.selectOne(
                new LambdaQueryWrapper<LlmProviderModel>()
                        .eq(LlmProviderModel::getProviderCode, parts[0].toLowerCase().trim())
                        .eq(LlmProviderModel::getModelName, parts[1].trim())
                        .eq(LlmProviderModel::getDeleted, 0)
                        .last("LIMIT 1")
        );
        return Optional.ofNullable(model);
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
