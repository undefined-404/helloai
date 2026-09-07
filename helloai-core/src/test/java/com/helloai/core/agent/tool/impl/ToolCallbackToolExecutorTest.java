package com.helloai.core.agent.tool.impl;

import com.helloai.core.agent.tool.ToolExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具执行回路单元测试（P0-C Phase 2）。
 *
 * <p>验证 {@link ToolCallbackToolExecutor} 的执行对偶契约：懒加载目录 + 按名调用
 * {@link ToolCallback}，覆盖成功透传 / 空参默认 / 未知工具 / 空名短路 / 异常降级 /
 * 目录加载失败降级 / 缓存复用。纯 Mockito 测试，spring-ai ToolCallback /
 * ToolCallbackProvider 均为接口可 mock。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolCallbackToolExecutor")
class ToolCallbackToolExecutorTest {

    @Mock
    private ToolCallbackProvider toolCallbackProvider;

    @Mock
    private ToolCallback echo;

    @Test
    @DisplayName("成功路径：工具输出透传，success=true 且 errorMessage=null")
    void shouldExecuteKnownToolAndPassOutput() {
        stubCatalog();
        when(echo.call("{\"message\":\"hi\"}")).thenReturn("{\"ok\":true,\"echoed\":\"hi\"}");

        ToolExecutionResult result = new ToolCallbackToolExecutor(toolCallbackProvider)
                .execute("echo", "{\"message\":\"hi\"}");

        assertThat(result.success()).isTrue();
        assertThat(result.toolName()).isEqualTo("echo");
        assertThat(result.output()).isEqualTo("{\"ok\":true,\"echoed\":\"hi\"}");
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    @DisplayName("空参/空白参按 {} 调用：回调收到空对象，结果透传")
    void shouldDefaultBlankArgumentsToEmptyObject() {
        stubCatalog();
        when(echo.call("{}")).thenReturn("{\"ok\":false,\"error\":\"missing message\"}");

        assertThat(new ToolCallbackToolExecutor(toolCallbackProvider)
                .execute("echo", null).success()).isTrue();
        assertThat(new ToolCallbackToolExecutor(toolCallbackProvider)
                .execute("echo", "  ").output()).contains("missing message");
    }

    @Test
    @DisplayName("未知工具 → success=false + 明确错误消息")
    void shouldFailOnUnknownTool() {
        stubCatalog();

        ToolExecutionResult result = new ToolCallbackToolExecutor(toolCallbackProvider)
                .execute("not-a-tool", "{}");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("not-a-tool");
        verify(echo, never()).call(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("空/空白工具名 → 直接失败且不触达 provider（短路）")
    void shouldFailOnBlankToolNameWithoutTouchingProvider() {
        ToolCallbackToolExecutor executor = new ToolCallbackToolExecutor(toolCallbackProvider);

        assertThat(executor.execute(null, "{}").success()).isFalse();
        assertThat(executor.execute("  ", "{}").success()).isFalse();
        assertThat(executor.execute(null, "{}").errorMessage()).isEqualTo("tool name is blank");
        verify(toolCallbackProvider, never()).getToolCallbacks();
    }

    @Test
    @DisplayName("回调抛异常 → success=false + 错误消息（best-effort 不抛）")
    void shouldFailWhenCallbackThrows() {
        stubCatalog();
        when(echo.call("{}")).thenThrow(new RuntimeException("boom"));

        ToolExecutionResult result = new ToolCallbackToolExecutor(toolCallbackProvider)
                .execute("echo", "{}");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("boom");
    }

    @Test
    @DisplayName("目录加载返回 null → 任何工具执行均失败（懒加载防御）")
    void shouldDegradeWhenProviderReturnsNull() {
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(null);

        ToolExecutionResult result = new ToolCallbackToolExecutor(toolCallbackProvider)
                .execute("echo", "{}");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("echo");
    }

    @Test
    @DisplayName("懒加载目录只触发一次（缓存复用）")
    void shouldLoadCatalogOnce() {
        stubCatalog();
        when(echo.call("{}")).thenReturn("ok");

        ToolCallbackToolExecutor executor = new ToolCallbackToolExecutor(toolCallbackProvider);
        executor.execute("echo", "{}");
        executor.execute("echo", "{}");

        verify(toolCallbackProvider, org.mockito.Mockito.times(1)).getToolCallbacks();
    }

    private void stubCatalog() {
        org.springframework.ai.tool.definition.ToolDefinition echoDef = definition("echo");
        when(echo.getToolDefinition()).thenReturn(echoDef);
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[]{echo});
    }

    private static org.springframework.ai.tool.definition.ToolDefinition definition(String name) {
        org.springframework.ai.tool.definition.ToolDefinition d =
                mock(org.springframework.ai.tool.definition.ToolDefinition.class);
        when(d.name()).thenReturn(name);
        return d;
    }
}
