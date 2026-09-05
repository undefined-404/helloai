package com.helloai.core.agent.tool.impl;

import com.helloai.core.agent.tool.ToolDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具注册表单元测试（Phase 1 Step 2）：
 * 懒加载目录（首次 resolve 触发）+ 按启用工具名解析命中元数据（启用/匹配契约）。
 * 纯 Mockito 测试，spring-ai ToolCallback / ToolDefinition 均为接口可 mock。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolRegistryImpl")
class ToolRegistryImplTest {

    @Mock
    private ToolCallbackProvider toolCallbackProvider;

    @Mock
    private ToolCallback pullTasks;

    @Mock
    private ToolCallback submitResult;

    @Mock
    private ToolCallback broken;

    @Test
    @DisplayName("should resolve enabled tool names to matched definitions in input order")
    void shouldResolveKnownToolsInInputOrder() {
        stubCatalog();

        List<ToolDefinition> resolved = new ToolRegistryImpl(toolCallbackProvider)
                .resolve(List.of("pullTasks", "submitResult"));

        assertThat(resolved).hasSize(2);
        assertThat(resolved.get(0).name()).isEqualTo("pullTasks");
        assertThat(resolved.get(0).description()).isEqualTo("拉取待处理收件箱");
        assertThat(resolved.get(1).name()).isEqualTo("submitResult");
        assertThat(resolved.get(1).description()).isEqualTo("上交执行结果");
    }

    @Test
    @DisplayName("should skip unknown tool names and keep known ones (启用/匹配契约)")
    void shouldSkipUnknownToolNames() {
        stubCatalog();

        List<ToolDefinition> resolved = new ToolRegistryImpl(toolCallbackProvider)
                .resolve(List.of("pullTasks", "not-a-tool", "submitResult"));

        assertThat(resolved).extracting(ToolDefinition::name)
                .containsExactly("pullTasks", "submitResult");
    }

    @Test
    @DisplayName("should return empty when enabledToolNames is null or empty (best-effort)")
    void shouldReturnEmptyForNullOrEmptyInput() {
        // 不 stub 目录：null/空入参直接短路，不触发目录加载（避免 UnnecessaryStubbing）
        ToolRegistryImpl registry = new ToolRegistryImpl(toolCallbackProvider);

        assertThat(registry.resolve(null)).isEmpty();
        assertThat(registry.resolve(List.of())).isEmpty();
    }

    @Test
    @DisplayName("should degrade to empty catalog when getToolCallbacks returns null (懒加载防御)")
    void shouldDegradeWhenProviderReturnsNull() {
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(null);

        ToolRegistryImpl registry = new ToolRegistryImpl(toolCallbackProvider);

        assertThat(registry.resolve(List.of("pullTasks"))).isEmpty();
    }

    @Test
    @DisplayName("should skip callbacks with null toolDefinition (装配防御)")
    void shouldSkipCallbackWithNullDefinition() {
        // broken 无有效 toolDefinition；pullTasks 正常——目录只含 pullTasks
        // 先构造定义再 stub（避免 thenReturn 参数内嵌套 when 触发 UnfinishedStubbing）
        org.springframework.ai.tool.definition.ToolDefinition pullDef =
                springDefinition("pullTasks", "拉取待处理收件箱");
        when(broken.getToolDefinition()).thenReturn(null);
        when(pullTasks.getToolDefinition()).thenReturn(pullDef);
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[]{broken, pullTasks});

        List<ToolDefinition> resolved = new ToolRegistryImpl(toolCallbackProvider)
                .resolve(List.of("pullTasks", "broken"));

        assertThat(resolved).extracting(ToolDefinition::name).containsExactly("pullTasks");
    }

    @Test
    @DisplayName("should load catalog once and reuse cache (懒加载只触发一次)")
    void shouldLoadCatalogOnce() {
        stubCatalog();
        ToolRegistryImpl registry = new ToolRegistryImpl(toolCallbackProvider);

        registry.resolve(List.of("pullTasks"));
        registry.resolve(List.of("pullTasks", "submitResult"));

        // 两次 resolve 只触发一次目录加载
        org.mockito.Mockito.verify(toolCallbackProvider, org.mockito.Mockito.times(1)).getToolCallbacks();
    }

    private void stubCatalog() {
        // 先构造定义再 stub（避免 thenReturn 参数内嵌套 when 触发 UnfinishedStubbing）
        org.springframework.ai.tool.definition.ToolDefinition pullDef =
                springDefinition("pullTasks", "拉取待处理收件箱");
        org.springframework.ai.tool.definition.ToolDefinition submitDef =
                springDefinition("submitResult", "上交执行结果");
        when(pullTasks.getToolDefinition()).thenReturn(pullDef);
        when(submitResult.getToolDefinition()).thenReturn(submitDef);
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[]{pullTasks, submitResult});
    }

    private static org.springframework.ai.tool.definition.ToolDefinition springDefinition(String name, String description) {
        org.springframework.ai.tool.definition.ToolDefinition definition =
                mock(org.springframework.ai.tool.definition.ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(definition.description()).thenReturn(description);
        return definition;
    }
}
