package com.helloai.core.system.service;

import com.helloai.common.base.BizException;
import com.helloai.core.system.entity.LlmProvider;
import com.helloai.core.system.mapper.LlmProviderMapper;
import com.helloai.core.system.service.impl.LlmProviderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * LlmProviderService 单元测试（方案B新增）。
 *
 * <p>覆盖：code 校验（格式 / 唯一 / 大小写）、protocol 校验、内置 Provider 不可改 code / 不可删、
 * 局部更新只覆盖非 null 字段、默认字段（enabled / builtin / sortOrder）初始化。</p>
 *
 * <p>本服务继承自 MyBatis-Plus 的 {@code ServiceImpl}，其 {@code baseMapper} 字段由 Spring 自动注入；
 * 单测环境中通过 {@link ReflectionTestUtils} 把 mock 注入到父类字段，让 {@code getById / updateById /
 * removeById} 等方法走 mock 而不是触发"baseMapper can not be null"。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LlmProviderService")
class LlmProviderServiceTest {

    @Mock
    private LlmProviderMapper mapper;

    @Mock
    private LlmProviderQueryService queryService;

    private LlmProviderServiceImpl service;

    @BeforeEach
    void setUp() {
        // 构造注入 queryService，再通过反射把 mock 注入到父类 ServiceImpl.baseMapper 字段
        // ServiceImpl.baseMapper 擦除类型为 BaseMapper，不带 type 参数避免类型不匹配
        service = new LlmProviderServiceImpl(queryService);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    @Test
    @DisplayName("create：code 自动转小写、默认字段填充、调用 save")
    void shouldCreateWithNormalizedFields() {
        when(queryService.findByCode("custom-gpt-4")).thenReturn(Optional.empty());

        LlmProvider entity = new LlmProvider();
        entity.setProviderCode("Custom-GPT-4");
        entity.setProviderName("我的 GPT-4");
        entity.setProtocolType("OPENAI_COMPATIBLE");
        entity.setBaseUrl("https://api.openai.com/v1");
        entity.setDefaultModel("gpt-4o-mini");

        LlmProvider saved = service.create(entity);

        // 原始入参被原地归一化为全小写
        assertThat(entity.getProviderCode()).isEqualTo("custom-gpt-4");
        assertThat(entity.getProtocolType()).isEqualTo("OPENAI_COMPATIBLE");
        // 默认字段填充
        assertThat(entity.getEnabled()).isEqualTo(1);
        assertThat(entity.getBuiltin()).isEqualTo(0);
        assertThat(entity.getSortOrder()).isEqualTo(100);
        // 返回值携带归一化后的 code
        assertThat(saved.getProviderCode()).isEqualTo("custom-gpt-4");
        // save 触发并以归一化后的实体落库
        ArgumentCaptor<LlmProvider> captor = ArgumentCaptor.forClass(LlmProvider.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getProviderCode()).isEqualTo("custom-gpt-4");
    }

    @Test
    @DisplayName("create：code 已存在时抛 BizException")
    void shouldRejectDuplicateCode() {
        LlmProvider existing = new LlmProvider();
        existing.setProviderCode("existing");
        when(queryService.findByCode("existing")).thenReturn(Optional.of(existing));

        LlmProvider entity = new LlmProvider();
        entity.setProviderCode("existing");
        entity.setProviderName("任意");
        entity.setProtocolType("OPENAI_COMPATIBLE");
        entity.setBaseUrl("https://x.com");

        assertThatThrownBy(() -> service.create(entity))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Provider 已存在");
    }

    @Test
    @DisplayName("create：code 格式不合法时抛 BizException")
    void shouldRejectInvalidCode() {
        LlmProvider a = new LlmProvider();
        a.setProviderCode(null);
        a.setProviderName("n");
        a.setProtocolType("OPENAI_COMPATIBLE");
        a.setBaseUrl("https://x.com");
        assertThatThrownBy(() -> service.create(a))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("provider_code 不能为空");

        // 大小写混合输入会被先归一化为全小写，通过校验
        LlmProvider b = new LlmProvider();
        b.setProviderCode("MIXED-Case");
        b.setProviderName("n");
        b.setProtocolType("OPENAI_COMPATIBLE");
        b.setBaseUrl("https://x.com");
        when(queryService.findByCode("mixed-case")).thenReturn(Optional.empty());
        LlmProvider saved = service.create(b);
        assertThat(saved.getProviderCode()).isEqualTo("mixed-case");

        // 归一化后仍不合法（仅 1 字符，不足 2 个字符）：测长度边界
        LlmProvider c = new LlmProvider();
        c.setProviderCode("a");
        c.setProviderName("n");
        c.setProtocolType("OPENAI_COMPATIBLE");
        c.setBaseUrl("https://x.com");
        assertThatThrownBy(() -> service.create(c))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("长度 2-64");

        // 归一化后仍不合法：包含非法字符（如 @、!），即使归一化也不匹配正则
        LlmProvider d = new LlmProvider();
        d.setProviderCode("Bad@Code");
        d.setProviderName("n");
        d.setProtocolType("OPENAI_COMPATIBLE");
        d.setBaseUrl("https://x.com");
        assertThatThrownBy(() -> service.create(d))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("provider_code 必须全小写");
    }

    @Test
    @DisplayName("create：protocol 不在白名单时抛 BizException")
    void shouldRejectInvalidProtocol() {
        LlmProvider entity = new LlmProvider();
        entity.setProviderCode("test");
        entity.setProviderName("t");
        entity.setProtocolType("GEMINI");
        entity.setBaseUrl("https://x.com");

        assertThatThrownBy(() -> service.create(entity))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("protocol_type 仅支持");
    }

    @Test
    @DisplayName("update：内置 Provider 不可改 providerCode")
    void shouldForbidBuiltinCodeChange() {
        LlmProvider existing = new LlmProvider();
        existing.setId(1L);
        existing.setProviderCode("deepseek");
        existing.setBuiltin(1);
        when(mapper.selectById(1L)).thenReturn(existing);

        LlmProvider patch = new LlmProvider();
        patch.setProviderCode("deepseek-renamed");

        assertThatThrownBy(() -> service.update(1L, patch))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("内置 Provider 不可修改 provider_code");
    }

    @Test
    @DisplayName("update：内置 Provider 修改其他字段允许")
    void shouldAllowBuiltinUpdateOtherFields() {
        LlmProvider existing = new LlmProvider();
        existing.setId(1L);
        existing.setProviderCode("deepseek");
        existing.setProviderName("DeepSeek");
        existing.setProtocolType("OPENAI_COMPATIBLE");
        existing.setBaseUrl("https://api.deepseek.com");
        existing.setBuiltin(1);
        when(mapper.selectById(1L)).thenReturn(existing);

        LlmProvider patch = new LlmProvider();
        patch.setProviderName("DeepSeek 官方");
        patch.setDefaultModel("deepseek-coder");

        service.update(1L, patch);

        ArgumentCaptor<LlmProvider> captor = ArgumentCaptor.forClass(LlmProvider.class);
        verify(mapper).updateById(captor.capture());
        LlmProvider updated = captor.getValue();
        assertThat(updated.getProviderName()).isEqualTo("DeepSeek 官方");
        assertThat(updated.getDefaultModel()).isEqualTo("deepseek-coder");
        assertThat(updated.getProviderCode()).isEqualTo("deepseek");
    }

    @Test
    @DisplayName("update：自定义 Provider 局部更新只覆盖非 null 字段")
    void shouldOnlyOverwriteNonNullFields() {
        LlmProvider existing = new LlmProvider();
        existing.setId(10L);
        existing.setProviderCode("custom");
        existing.setProviderName("原始名");
        existing.setProtocolType("OPENAI_COMPATIBLE");
        existing.setBaseUrl("https://old.example.com");
        existing.setDefaultModel("old-model");
        existing.setEnabled(1);
        existing.setBuiltin(0);
        when(mapper.selectById(10L)).thenReturn(existing);

        LlmProvider patch = new LlmProvider();
        patch.setBaseUrl("https://new.example.com");

        service.update(10L, patch);

        ArgumentCaptor<LlmProvider> captor = ArgumentCaptor.forClass(LlmProvider.class);
        verify(mapper).updateById(captor.capture());
        LlmProvider updated = captor.getValue();
        assertThat(updated.getBaseUrl()).isEqualTo("https://new.example.com");
        assertThat(updated.getProviderName()).isEqualTo("原始名");
        assertThat(updated.getProtocolType()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(updated.getDefaultModel()).isEqualTo("old-model");
    }

    @Test
    @DisplayName("deleteById：内置 Provider 不可删")
    void shouldForbidBuiltinDeletion() {
        LlmProvider existing = new LlmProvider();
        existing.setId(1L);
        existing.setProviderCode("deepseek");
        existing.setBuiltin(1);
        when(mapper.selectById(1L)).thenReturn(existing);

        assertThatThrownBy(() -> service.deleteById(1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("内置 Provider 不可删除");
        verify(mapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("deleteById：自定义 Provider 可删")
    void shouldAllowCustomDeletion() {
        LlmProvider existing = new LlmProvider();
        existing.setId(10L);
        existing.setProviderCode("custom");
        existing.setBuiltin(0);
        when(mapper.selectById(10L)).thenReturn(existing);

        service.deleteById(10L);

        verify(mapper).deleteById(10L);
    }
}
