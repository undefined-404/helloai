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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LlmProviderModelServiceImpl#addModel} 的 `M-9` 谓词落库单测。
 *
 * <p>{@code V112} 摘掉 {@code capabilities} 列默认值后，新增模型必须<b>显式写谓词</b>，
 * 否则 NOT NULL 违约（「添加模型」500）。本条锁定「新行谓词非空且为保守面」。</p>
 */
@ExtendWith(MockitoExtension.class)
class LlmProviderModelServiceImplAddModelTest {

    @Mock
    private LlmProviderModelQueryService llmProviderModelQueryService;

    @Test
    @DisplayName("addModel：新行显式写保守面谓词（非空；text-only/无 reasoning/无工具/无联网）")
    void addModelWritesConservativePredicate() {
        LlmProviderModelServiceImpl service =
                Mockito.spy(new LlmProviderModelServiceImpl(llmProviderModelQueryService));
        when(llmProviderModelQueryService.countByProviderId(2L)).thenReturn(0L);
        // 绕开 ServiceImpl 的 DB 依赖：存在性计数与落库均打桩
        doReturn(0L).when(service).count(any(Wrapper.class));
        doReturn(true).when(service).save(any(LlmProviderModel.class));

        service.addModel(2L, "myprov", "my-model", false);

        ArgumentCaptor<LlmProviderModel> captor = ArgumentCaptor.forClass(LlmProviderModel.class);
        verify(service).save(captor.capture());
        LlmProviderModel row = captor.getValue();
        assertThat(row.getCapabilities()).isNotNull();
        assertThat(row.getCapabilities())
                .containsKeys("input", "output", "reasoning", "toolCalling", "webSearch");
        assertThat(ModelCapabilities.fromRaw(row.getCapabilities()))
                .isEqualTo(ModelCapabilities.conservative());
    }
}
