package com.helloai.core.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.common.base.BizException;
import com.helloai.core.system.entity.LlmProviderModel;
import com.helloai.core.system.mapper.LlmProviderModelMapper;
import com.helloai.core.system.service.impl.LlmProviderModelServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LlmProviderModelServiceImpl 单元测试（V49 新增）。
 *
 * <p>覆盖：批量保存（空列表 / 默认模型不在列表 / 正常替换）、默认模型设置、
 * 添加模型（重复 / 默认互斥）、删除模型（默认模型 / 最后一个拒绝）、
 * 启用/禁用（默认模型禁用保护）、Provider 启用模型校验。</p>
 *
 * <p>本服务继承自 MyBatis-Plus 的 {@code ServiceImpl}，其 {@code baseMapper} 字段由 Spring
 * 自动注入；单测环境中通过 {@link ReflectionTestUtils} 把 mock 注入到父类字段，让
 * {@code save / remove / getOne / update / updateById / count / removeById} 等方法走 mock。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LlmProviderModelServiceImpl")
class LlmProviderModelServiceImplTest {

    @Mock
    private LlmProviderModelMapper mapper;

    @Mock
    private LlmProviderModelQueryService queryService;

    private LlmProviderModelServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LlmProviderModelServiceImpl(queryService);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    private LlmProviderModel model(Long id, String name, int isDefault, int enabled) {
        LlmProviderModel m = new LlmProviderModel();
        m.setId(id);
        m.setProviderId(1L);
        m.setProviderCode("deepseek");
        m.setModelName(name);
        m.setIsDefault(isDefault);
        m.setEnabled(enabled);
        return m;
    }

    // ── saveProviderModels ──

    @Test
    @DisplayName("saveProviderModels：providerId 为空时抛 BizException")
    void saveProviderModels_nullProviderId_throws() {
        assertThatThrownBy(() -> service.saveProviderModels(null, "deepseek", List.of("m1"), "m1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Provider ID 不能为空");
    }

    @Test
    @DisplayName("saveProviderModels：模型列表为空时抛 BizException")
    void saveProviderModels_emptyModelNames_throws() {
        assertThatThrownBy(() -> service.saveProviderModels(1L, "deepseek", List.of(), "m1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请至少选择一个可用模型");
        assertThatThrownBy(() -> service.saveProviderModels(1L, "deepseek", null, "m1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请至少选择一个可用模型");
    }

    @Test
    @DisplayName("saveProviderModels：默认模型为空或不在列表中时抛 BizException")
    void saveProviderModels_invalidDefaultModel_throws() {
        assertThatThrownBy(() -> service.saveProviderModels(1L, "deepseek", List.of("m1"), " "))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请设置默认模型");
        assertThatThrownBy(() -> service.saveProviderModels(1L, "deepseek", List.of("m1"), "m2"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("默认模型必须在已选模型列表中");
    }

    @Test
    @DisplayName("saveProviderModels：正常保存时先删除旧模型再插入，默认模型标记正确")
    void saveProviderModels_valid_replacesAllAndMarksDefault() {
        when(mapper.delete(any())).thenReturn(1);

        service.saveProviderModels(1L, "deepseek", List.of("deepseek-v4-flash", "deepseek-v4-pro"), "deepseek-v4-flash");

        verify(mapper).delete(any(LambdaQueryWrapper.class));
        ArgumentCaptor<LlmProviderModel> captor = ArgumentCaptor.forClass(LlmProviderModel.class);
        verify(mapper, times(2)).insert(captor.capture());
        List<LlmProviderModel> inserted = captor.getAllValues();
        assertThat(inserted).extracting(LlmProviderModel::getModelName)
                .containsExactly("deepseek-v4-flash", "deepseek-v4-pro");
        assertThat(inserted.get(0).getIsDefault()).isEqualTo(1);
        assertThat(inserted.get(1).getIsDefault()).isZero();
        assertThat(inserted.get(0).getProviderCode()).isEqualTo("deepseek");
        assertThat(inserted.get(0).getEnabled()).isEqualTo(1);
    }

    // ── setDefaultModel ──

    @Test
    @DisplayName("setDefaultModel：模型不存在或未启用时抛 BizException")
    void setDefaultModel_targetMissing_throws() {
        when(mapper.selectOne(any(), anyBoolean())).thenReturn(null);

        assertThatThrownBy(() -> service.setDefaultModel(1L, "deepseek-v4-pro"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("模型不存在或未启用");
    }

    @Test
    @DisplayName("setDefaultModel：正常设置时先清除旧默认再标记新默认")
    void setDefaultModel_valid_clearsOldAndSetsNew() {
        LlmProviderModel target = model(2L, "deepseek-v4-pro", 0, 1);
        when(mapper.selectOne(any(), anyBoolean())).thenReturn(target);
        when(mapper.update(any(), any())).thenReturn(1);
        when(mapper.updateById(any(LlmProviderModel.class))).thenReturn(1);

        service.setDefaultModel(1L, "deepseek-v4-pro");

        // 先批量清除 isDefault=1 的记录，再更新目标
        verify(mapper).update(any(LlmProviderModel.class), any(LambdaQueryWrapper.class));
        verify(mapper).updateById(target);
        assertThat(target.getIsDefault()).isEqualTo(1);
    }

    // ── addModel ──

    @Test
    @DisplayName("addModel：模型已存在时抛 BizException")
    void addModel_duplicate_throws() {
        when(mapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.addModel(1L, "deepseek", "deepseek-v4-flash", false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("模型已存在");
        verify(mapper, never()).insert(any(LlmProviderModel.class));
    }

    @Test
    @DisplayName("addModel：新增默认模型时清除其他默认标记")
    void addModel_isDefault_clearsOthers() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(queryService.countByProviderId(1L)).thenReturn(2L);

        service.addModel(1L, "deepseek", "deepseek-v4-pro", true);

        verify(mapper).update(any(LlmProviderModel.class), any(LambdaQueryWrapper.class));
        ArgumentCaptor<LlmProviderModel> captor = ArgumentCaptor.forClass(LlmProviderModel.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getIsDefault()).isEqualTo(1);
        assertThat(captor.getValue().getEnabled()).isEqualTo(1);
        // 排序追加到现有数量之后
        assertThat(captor.getValue().getSortOrder()).isEqualTo(120);
    }

    @Test
    @DisplayName("addModel：新增非默认模型不触发清除操作")
    void addModel_normal_noClearOthers() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(queryService.countByProviderId(1L)).thenReturn(1L);

        service.addModel(1L, "deepseek", "deepseek-v4-pro", false);

        verify(mapper, never()).update(any(LlmProviderModel.class), any(LambdaQueryWrapper.class));
        verify(mapper).insert(any(LlmProviderModel.class));
    }

    // ── deleteModel ──

    @Test
    @DisplayName("deleteModel：模型不存在时抛 BizException")
    void deleteModel_missing_throws() {
        when(mapper.selectOne(any(), anyBoolean())).thenReturn(null);

        assertThatThrownBy(() -> service.deleteModel(1L, "deepseek-v4-pro"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("模型不存在");
    }

    @Test
    @DisplayName("deleteModel：删除默认模型被拒绝")
    void deleteModel_defaultModel_throws() {
        when(mapper.selectOne(any(), anyBoolean())).thenReturn(model(2L, "deepseek-v4-flash", 1, 1));

        assertThatThrownBy(() -> service.deleteModel(1L, "deepseek-v4-flash"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能删除默认模型");
        verify(mapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("deleteModel：删除最后一个模型被拒绝")
    void deleteModel_lastModel_throws() {
        when(mapper.selectOne(any(), anyBoolean())).thenReturn(model(2L, "deepseek-v4-pro", 0, 1));
        when(queryService.countByProviderId(1L)).thenReturn(1L);

        assertThatThrownBy(() -> service.deleteModel(1L, "deepseek-v4-pro"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能删除最后一个模型");
    }

    @Test
    @DisplayName("deleteModel：正常删除非默认模型")
    void deleteModel_normal_removes() {
        when(mapper.selectOne(any(), anyBoolean())).thenReturn(model(2L, "deepseek-v4-pro", 0, 1));
        when(queryService.countByProviderId(1L)).thenReturn(2L);

        service.deleteModel(1L, "deepseek-v4-pro");

        verify(mapper).deleteById(2L);
    }

    // ── toggleModel ──

    @Test
    @DisplayName("toggleModel：禁用最后一个启用模型（默认或非默认）时被拒绝")
    void toggleModel_disableDefaultWithNoOtherEnabled_throws() {
        when(mapper.selectOne(any(), anyBoolean())).thenReturn(model(2L, "deepseek-v4-flash", 1, 1));
        when(mapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.toggleModel(1L, "deepseek-v4-flash", false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能禁用最后一个启用模型");
        verify(mapper, never()).updateById(any(LlmProviderModel.class));
    }

    @Test
    @DisplayName("toggleModel：禁用非默认模型且无其他启用模型时同样被拒绝")
    void toggleModel_disableNonDefaultLastEnabled_throws() {
        when(mapper.selectOne(any(), anyBoolean())).thenReturn(model(2L, "deepseek-v4-pro", 0, 1));
        when(mapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.toggleModel(1L, "deepseek-v4-pro", false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能禁用最后一个启用模型");
        verify(mapper, never()).updateById(any(LlmProviderModel.class));
    }

    @Test
    @DisplayName("toggleModel：正常启用/禁用非默认模型（存在其他启用模型）")
    void toggleModel_valid_toggles() {
        LlmProviderModel target = model(2L, "deepseek-v4-pro", 0, 1);
        when(mapper.selectOne(any(), anyBoolean())).thenReturn(target);
        when(mapper.selectCount(any())).thenReturn(1L);
        when(mapper.updateById(any(LlmProviderModel.class))).thenReturn(1);

        service.toggleModel(1L, "deepseek-v4-pro", false);

        assertThat(target.getEnabled()).isZero();
        verify(mapper).updateById(target);
    }

    @Test
    @DisplayName("toggleModel：禁用默认模型但存在其他启用模型时允许")
    void toggleModel_disableDefaultWithOtherEnabled_ok() {
        LlmProviderModel target = model(2L, "deepseek-v4-flash", 1, 1);
        when(mapper.selectOne(any(), anyBoolean())).thenReturn(target);
        when(mapper.selectCount(any())).thenReturn(1L);
        when(mapper.updateById(any(LlmProviderModel.class))).thenReturn(1);

        service.toggleModel(1L, "deepseek-v4-flash", false);

        assertThat(target.getEnabled()).isZero();
        verify(mapper).updateById(target);
    }

    // ── validateProviderHasEnabledModels ──

    @Test
    @DisplayName("validateProviderHasEnabledModels：存在启用模型时通过")
    void validateProviderHasEnabledModels_hasEnabled_passes() {
        when(mapper.selectCount(any())).thenReturn(2L);

        service.validateProviderHasEnabledModels(1L);
    }

    @Test
    @DisplayName("validateProviderHasEnabledModels：无启用模型时抛 BizException")
    void validateProviderHasEnabledModels_noneEnabled_throws() {
        when(mapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.validateProviderHasEnabledModels(1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Provider 必须至少有一个启用模型");
    }
}
