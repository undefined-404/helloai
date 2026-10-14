package com.helloai.core.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.core.system.entity.LlmProviderModel;
import com.helloai.core.system.mapper.LlmProviderModelMapper;
import com.helloai.core.system.port.ModelCapabilities;
import com.helloai.core.system.service.LlmProviderModelQueryService;
import com.helloai.core.system.service.LlmProviderModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM Provider 模型业务服务实现。
 *
 * <p>负责 Provider 模型的 CRUD、默认模型管理、启用/禁用切换。
 * 核心约束：每个 Provider 必须至少有一个启用模型，且必须有一个默认模型。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderModelServiceImpl extends ServiceImpl<LlmProviderModelMapper, LlmProviderModel>
        implements LlmProviderModelService {

    private final LlmProviderModelQueryService llmProviderModelQueryService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveProviderModels(Long providerId, String providerCode, List<String> modelNames, String defaultModel) {
        if (providerId == null) {
            throw new BizException("Provider ID 不能为空");
        }
        if (modelNames == null || modelNames.isEmpty()) {
            throw new BizException("请至少选择一个可用模型");
        }
        if (defaultModel == null || defaultModel.isBlank()) {
            throw new BizException("请设置默认模型");
        }
        if (!modelNames.contains(defaultModel)) {
            throw new BizException("默认模型必须在已选模型列表中");
        }

        // `M-9`（QA-M9-04）：本次是「先删后插」整表重写，重插必须**复用既有行的能力谓词**，
        // 否则「编辑 provider 模型列表」这种常规动作会把 reasoning / webSearch / input 模态
        // 静默清零（比改造前多丢 thinking），且静态守卫与 `V110` 的一次性断言都抓不到。
        // ⇒ 读旧行必须在 `remove` **之前**：`remove` 走 @TableLogic 逻辑删除（SET deleted=1），
        //   删后默认查询已看不到这些行，故先建「归一 modelName → 旧谓词」索引。
        Map<String, Map<String, Object>> priorCapabilities = new LinkedHashMap<>();
        List<LlmProviderModel> priorModels = list(new LambdaQueryWrapper<LlmProviderModel>()
                .eq(LlmProviderModel::getProviderId, providerId));
        if (priorModels != null) {
            for (LlmProviderModel prior : priorModels) {
                if (prior.getModelName() == null) {
                    continue;
                }
                priorCapabilities.put(prior.getModelName().trim(), prior.getCapabilities());
            }
        }

        // 先删除该 Provider 的所有现有模型（@TableLogic 逻辑删除；部分唯一索引
        // uk_provider_model_active 仅约束 deleted=0 ⇒ 逻辑删后同 (provider_id, model_name) 可重插）
        remove(new LambdaQueryWrapper<LlmProviderModel>()
                .eq(LlmProviderModel::getProviderId, providerId));

        // 插入新选择的模型
        int sortOrder = 10;
        for (String modelName : modelNames) {
            if (modelName == null || modelName.isBlank()) {
                continue;
            }
            // 键的归一与插入侧一致：插入用 modelName.trim()，故复用查表也用同一套归一，
            // 否则 " x " 与 "x" 会被判成两把而丢谓词。
            String normalizedName = modelName.trim();
            LlmProviderModel model = new LlmProviderModel();
            model.setProviderId(providerId);
            model.setProviderCode(providerCode);
            model.setModelName(normalizedName);
            model.setIsDefault(normalizedName.equals(defaultModel) ? 1 : 0);
            model.setEnabled(1);
            model.setSortOrder(sortOrder);
            // `V112` 已摘掉 capabilities 列默认值 ⇒ 任何 INSERT 必须显式写谓词（否则 NOT NULL 违约）。
            // 优先复用既有谓词（逐字保留，含未知模态 token）；仅当该 modelName 原本不存在、
            // 或旧谓词为 null/空 时才落保守面（对齐 V110 列默认为空的 fail-closed 口径）。
            Map<String, Object> reused = priorCapabilities.get(normalizedName);
            model.setCapabilities(reused == null || reused.isEmpty()
                    ? ModelCapabilities.conservative().toRawMap()
                    : reused);
            sortOrder += 10;
            save(model);
        }

        log.info("Provider 模型配置已保存: providerId={}, providerCode={}, models={}, default={}",
                providerId, providerCode, modelNames, defaultModel);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefaultModel(Long providerId, String modelName) {
        if (providerId == null || modelName == null || modelName.isBlank()) {
            throw new BizException("Provider ID 和模型名称不能为空");
        }

        // 校验模型存在且启用
        LlmProviderModel target = getOne(new LambdaQueryWrapper<LlmProviderModel>()
                .eq(LlmProviderModel::getProviderId, providerId)
                .eq(LlmProviderModel::getModelName, modelName.trim())
                .eq(LlmProviderModel::getEnabled, 1)
                .eq(LlmProviderModel::getDeleted, 0));
        if (target == null) {
            throw new BizException("模型不存在或未启用: " + modelName);
        }

        // 清除该 Provider 的所有默认标记
        LlmProviderModel clearDefault = new LlmProviderModel();
        clearDefault.setIsDefault(0);
        update(clearDefault, new LambdaQueryWrapper<LlmProviderModel>()
                .eq(LlmProviderModel::getProviderId, providerId)
                .eq(LlmProviderModel::getIsDefault, 1));

        // 设置新的默认模型
        target.setIsDefault(1);
        updateById(target);

        log.info("Provider 默认模型已设置: providerId={}, modelName={}", providerId, modelName);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LlmProviderModel addModel(Long providerId, String providerCode, String modelName, boolean isDefault) {
        if (providerId == null || modelName == null || modelName.isBlank()) {
            throw new BizException("Provider ID 和模型名称不能为空");
        }

        // 校验模型是否已存在
        long count = count(new LambdaQueryWrapper<LlmProviderModel>()
                .eq(LlmProviderModel::getProviderId, providerId)
                .eq(LlmProviderModel::getModelName, modelName.trim())
                .eq(LlmProviderModel::getDeleted, 0));
        if (count > 0) {
            throw new BizException("模型已存在: " + modelName);
        }

        // 如果是默认模型，先清除其他默认
        if (isDefault) {
            LlmProviderModel clearDefault = new LlmProviderModel();
            clearDefault.setIsDefault(0);
            update(clearDefault, new LambdaQueryWrapper<LlmProviderModel>()
                    .eq(LlmProviderModel::getProviderId, providerId)
                    .eq(LlmProviderModel::getIsDefault, 1));
        }

        LlmProviderModel model = new LlmProviderModel();
        model.setProviderId(providerId);
        model.setProviderCode(providerCode);
        model.setModelName(modelName.trim());
        model.setIsDefault(isDefault ? 1 : 0);
        model.setEnabled(1);
        // 排序放到最后
        long maxSort = llmProviderModelQueryService.countByProviderId(providerId);
        model.setSortOrder((int) (maxSort * 10 + 100));
        // `M-9`：`V112` 摘掉 capabilities 列默认值后，新增模型必须显式写谓词（否则 NOT NULL 违约 →
        // 「添加模型」500）。新行能力未知 ⇒ 保守面（text-only、无 reasoning/工具/联网，fail-closed）。
        model.setCapabilities(ModelCapabilities.conservative().toRawMap());
        save(model);

        log.info("Provider 模型已添加: providerId={}, modelName={}, isDefault={}", providerId, modelName, isDefault);
        return model;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteModel(Long providerId, String modelName) {
        if (providerId == null || modelName == null || modelName.isBlank()) {
            throw new BizException("Provider ID 和模型名称不能为空");
        }

        LlmProviderModel model = getOne(new LambdaQueryWrapper<LlmProviderModel>()
                .eq(LlmProviderModel::getProviderId, providerId)
                .eq(LlmProviderModel::getModelName, modelName.trim())
                .eq(LlmProviderModel::getDeleted, 0));
        if (model == null) {
            throw new BizException("模型不存在: " + modelName);
        }

        // 校验是否是默认模型
        if (Integer.valueOf(1).equals(model.getIsDefault())) {
            throw new BizException("不能删除默认模型，请先设置其他模型为默认");
        }

        // 校验是否是最后一个模型
        long totalCount = llmProviderModelQueryService.countByProviderId(providerId);
        if (totalCount <= 1) {
            throw new BizException("不能删除最后一个模型，Provider 必须至少有一个可用模型");
        }

        removeById(model.getId());
        log.info("Provider 模型已删除: providerId={}, modelName={}", providerId, modelName);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void toggleModel(Long providerId, String modelName, boolean enabled) {
        if (providerId == null || modelName == null || modelName.isBlank()) {
            throw new BizException("Provider ID 和模型名称不能为空");
        }

        LlmProviderModel model = getOne(new LambdaQueryWrapper<LlmProviderModel>()
                .eq(LlmProviderModel::getProviderId, providerId)
                .eq(LlmProviderModel::getModelName, modelName.trim())
                .eq(LlmProviderModel::getDeleted, 0));
        if (model == null) {
            throw new BizException("模型不存在: " + modelName);
        }

        // 禁用模型时校验是否还有其他启用模型（默认模型与普通模型均适用）
        if (!enabled) {
            long enabledCount = count(new LambdaQueryWrapper<LlmProviderModel>()
                    .eq(LlmProviderModel::getProviderId, providerId)
                    .eq(LlmProviderModel::getEnabled, 1)
                    .eq(LlmProviderModel::getDeleted, 0)
                    .ne(LlmProviderModel::getId, model.getId()));
            if (enabledCount == 0) {
                throw new BizException("不能禁用最后一个启用模型，Provider 必须至少有一个启用模型");
            }
        }

        model.setEnabled(enabled ? 1 : 0);
        updateById(model);
        log.info("Provider 模型已{}: providerId={}, modelName={}", enabled ? "启用" : "禁用", providerId, modelName);
    }

    @Override
    public void validateProviderHasEnabledModels(Long providerId) {
        if (providerId == null) {
            throw new BizException("Provider ID 不能为空");
        }
        long enabledCount = count(new LambdaQueryWrapper<LlmProviderModel>()
                .eq(LlmProviderModel::getProviderId, providerId)
                .eq(LlmProviderModel::getEnabled, 1)
                .eq(LlmProviderModel::getDeleted, 0));
        if (enabledCount == 0) {
            throw new BizException("Provider 必须至少有一个启用模型");
        }
    }

    @Override
    public void deleteAllPhysicalByProviderId(Long providerId) {
        if (providerId == null) {
            return;
        }
        baseMapper.deletePhysicalByProviderId(providerId);
    }
}

