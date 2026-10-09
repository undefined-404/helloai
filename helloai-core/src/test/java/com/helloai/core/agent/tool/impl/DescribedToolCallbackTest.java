package com.helloai.core.agent.tool.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DescribedToolCallback} 单元测试（REF-1.3 动态描述生效面）：
 * 只替换 description（name / inputSchema 逐字不变），执行面全量委托（零语义变化）。
 *
 * <p>本测试里的 {@code ToolContext} 是 <b>spring-ai 执行期</b>那个
 * （{@code org.springframework.ai.chat.model}）——与本类同源：{@code DescribedToolCallback}
 * 是全库唯一同时接触它的文件（另一侧的解析期 {@code com.helloai.core.agent.tool.ToolContext}
 * 在包装类中不出现）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DescribedToolCallback")
class DescribedToolCallbackTest {

    private static final String INPUT_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    @Mock
    private ToolCallback delegate;

    @Test
    @DisplayName("getToolDefinition：仅 description 被替换，name / inputSchema 逐字不变")
    void shouldReplaceOnlyDescription() {
        when(delegate.getToolDefinition()).thenReturn(
                definition("web_search", "旧描述", INPUT_SCHEMA));

        ToolDefinition result = new DescribedToolCallback(delegate, "本轮新描述").getToolDefinition();

        assertThat(result.description()).isEqualTo("本轮新描述");
        assertThat(result.name()).isEqualTo("web_search");
        assertThat(result.inputSchema()).isEqualTo(INPUT_SCHEMA);
    }

    @Test
    @DisplayName("执行语义零变化：call / call(input, ctx) / getToolMetadata 全量委托 delegate")
    void shouldDelegateExecutionSurface() {
        ToolMetadata metadata = mock(ToolMetadata.class);
        ToolContext executionContext = new ToolContext(Map.of("sessionId", "s-1"));
        when(delegate.getToolMetadata()).thenReturn(metadata);
        when(delegate.call("{}")).thenReturn("direct");
        when(delegate.call("{}", executionContext)).thenReturn("with-context");

        ToolCallback wrapped = new DescribedToolCallback(delegate, "新描述");

        assertThat(wrapped.getToolMetadata()).isSameAs(metadata);
        assertThat(wrapped.call("{}")).isEqualTo("direct");
        assertThat(wrapped.call("{}", executionContext)).isEqualTo("with-context");
        verify(delegate).call("{}");
        verify(delegate).call("{}", executionContext);
    }

    private static ToolDefinition definition(String name, String description, String inputSchema) {
        return ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
    }
}
