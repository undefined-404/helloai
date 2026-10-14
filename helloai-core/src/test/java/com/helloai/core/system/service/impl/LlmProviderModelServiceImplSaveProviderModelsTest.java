package com.helloai.core.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.helloai.core.system.entity.LlmProviderModel;
import com.helloai.core.system.port.ModelCapabilities;
import com.helloai.core.system.service.LlmProviderModelQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link LlmProviderModelServiceImpl#saveProviderModels} 的 `M-9` 谓词复用单测（QA-M9-04）。
 *
 * <p>{@code saveProviderModels} 是「先删后插」整表重写。`V112` 摘掉 {@code capabilities}
 * 列默认值后，重插若一律落保守面，会把 {@code reasoning} / {@code webSearch} / {@code input}
 * 模态静默清零（比改造前多丢 {@code thinking}）——即「编辑 provider 模型列表」这种常规动作
 * 就抹掉能力谓词。本条锁定：<b>重插优先复用既有行谓词，仅真正的新模型才落保守面</b>。</p>
 */
@ExtendWith(MockitoExtension.class)
class LlmProviderModelServiceImplSaveProviderModelsTest {

    @Mock
    private LlmProviderModelQueryService llmProviderModelQueryService;

    private LlmProviderModelServiceImpl newService() {
        LlmProviderModelServiceImpl service =
                Mockito.spy(new LlmProviderModelServiceImpl(llmProviderModelQueryService));
        // 绕开 ServiceImpl 的 DB 依赖：删除与落库均打桩（list 由各用例按场景打桩）
        doReturn(true).when(service).remove(any(Wrapper.class));
        doReturn(true).when(service).save(any(LlmProviderModel.class));
        return service;
    }

    private LlmProviderModel priorRow(Long providerId, String modelName, Map<String, Object> caps) {
        LlmProviderModel row = new LlmProviderModel();
        row.setProviderId(providerId);
        row.setProviderCode("myprov");
        row.setModelName(modelName);
        row.setCapabilities(caps);
        return row;
    }

    /** 非保守面谓词：reasoning=true + webSearch=true + 未知模态 token {@code pdf}。 */
    private Map<String, Object> richCapabilities() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("input", List.of("text", "pdf"));
        raw.put("output", List.of("text"));
        raw.put("reasoning", true);
        raw.put("toolCalling", false);
        raw.put("webSearch", true);
        return raw;
    }

    @Test
    @DisplayName("saveProviderModels：既有模型谓词逐字复用（不丢 reasoning / 不丢 webSearch / pdf 仍在）")
    void reuseExistingCapabilities() {
        LlmProviderModelServiceImpl service = newService();
        doReturn(List.of(priorRow(7L, "my-model", richCapabilities())))
                .when(service).list(any(Wrapper.class));

        service.saveProviderModels(7L, "myprov", List.of("my-model"), "my-model");

        ArgumentCaptor<LlmProviderModel> captor = ArgumentCaptor.forClass(LlmProviderModel.class);
        verify(service).save(captor.capture());
        LlmProviderModel row = captor.getValue();
        // 逐字保留（与独立构造的期望 map 相等；若实现改为写保守面则此处必红）
        assertThat(row.getCapabilities()).isEqualTo(richCapabilities());
        ModelCapabilities caps = ModelCapabilities.fromRaw(row.getCapabilities());
        assertThat(caps.reasoning()).isTrue();
        assertThat(caps.webSearch()).isTrue();
        assertThat(caps.supportsInput("pdf")).isTrue();
    }

    @Test
    @DisplayName("saveProviderModels：原本不存在的 modelName ⇒ 落保守面")
    void newModelNameGetsConservative() {
        LlmProviderModelServiceImpl service = newService();
        doReturn(List.of(priorRow(7L, "other-model", richCapabilities())))
                .when(service).list(any(Wrapper.class));

        service.saveProviderModels(7L, "myprov", List.of("brand-new"), "brand-new");

        ArgumentCaptor<LlmProviderModel> captor = ArgumentCaptor.forClass(LlmProviderModel.class);
        verify(service).save(captor.capture());
        assertThat(captor.getValue().getCapabilities()).isNotNull();
        assertThat(ModelCapabilities.fromRaw(captor.getValue().getCapabilities()))
                .isEqualTo(ModelCapabilities.conservative());
    }

    @Test
    @DisplayName("saveProviderModels：既有行 capabilities 为 null ⇒ 落保守面且不 NPE")
    void nullExistingCapabilitiesGetsConservative() {
        LlmProviderModelServiceImpl service = newService();
        doReturn(List.of(priorRow(7L, "my-model", null)))
                .when(service).list(any(Wrapper.class));

        service.saveProviderModels(7L, "myprov", List.of("my-model"), "my-model");

        ArgumentCaptor<LlmProviderModel> captor = ArgumentCaptor.forClass(LlmProviderModel.class);
        verify(service).save(captor.capture());
        assertThat(captor.getValue().getCapabilities()).isNotNull();
        assertThat(ModelCapabilities.fromRaw(captor.getValue().getCapabilities()))
                .isEqualTo(ModelCapabilities.conservative());
    }

    @Test
    @DisplayName("saveProviderModels：键归一一致（旧 \" x \" 与入参 \"x\" 视为同一模型 ⇒ 复用谓词）")
    void normalizationTrimMatchesExisting() {
        LlmProviderModelServiceImpl service = newService();
        doReturn(List.of(priorRow(7L, " x ", richCapabilities())))
                .when(service).list(any(Wrapper.class));

        service.saveProviderModels(7L, "myprov", List.of("x"), "x");

        ArgumentCaptor<LlmProviderModel> captor = ArgumentCaptor.forClass(LlmProviderModel.class);
        verify(service).save(captor.capture());
        LlmProviderModel row = captor.getValue();
        assertThat(row.getModelName()).isEqualTo("x");
        assertThat(row.getCapabilities()).isEqualTo(richCapabilities());
    }

    @Test
    @DisplayName("saveProviderModels：多模型混合 —— 既有行复用、新行保守面，且 isDefault/sortOrder 语义不变")
    void mixedReuseAndNewRow() {
        LlmProviderModelServiceImpl service = newService();
        doReturn(List.of(priorRow(7L, "keep", richCapabilities())))
                .when(service).list(any(Wrapper.class));

        service.saveProviderModels(7L, "myprov", List.of("keep", "fresh"), "keep");

        ArgumentCaptor<LlmProviderModel> captor = ArgumentCaptor.forClass(LlmProviderModel.class);
        verify(service, times(2)).save(captor.capture());
        List<LlmProviderModel> rows = captor.getAllValues();

        assertThat(rows.get(0).getModelName()).isEqualTo("keep");
        assertThat(rows.get(0).getCapabilities()).isEqualTo(richCapabilities());
        assertThat(rows.get(0).getIsDefault()).isEqualTo(1);
        assertThat(rows.get(0).getSortOrder()).isEqualTo(10);
        assertThat(rows.get(0).getEnabled()).isEqualTo(1);

        assertThat(rows.get(1).getModelName()).isEqualTo("fresh");
        assertThat(ModelCapabilities.fromRaw(rows.get(1).getCapabilities()))
                .isEqualTo(ModelCapabilities.conservative());
        assertThat(rows.get(1).getIsDefault()).isEqualTo(0);
        assertThat(rows.get(1).getSortOrder()).isEqualTo(20);
        assertThat(rows.get(1).getEnabled()).isEqualTo(1);
    }
}
