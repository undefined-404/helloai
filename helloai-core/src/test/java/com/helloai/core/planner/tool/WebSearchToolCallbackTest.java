package com.helloai.core.planner.tool;

import com.helloai.core.planner.search.WebSearchResult;
import com.helloai.core.planner.service.WebSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WebSearchToolCallback} 单元测试（阶段四 Capability 化）：
 * 成功命中 / 空关键词空结果 / maxResults clamp / 异常降级不抛 / withAnswer 填充 /
 * toolObject 返回自身（agent 域收集器要求原始对象，代理会丢 @Tool 注解）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebSearchToolCallback")
class WebSearchToolCallbackTest {

    @Mock
    private WebSearchService webSearchService;

    private WebSearchToolCallback tool;

    @BeforeEach
    void setUp() {
        // @Mock 注入发生在构造器前，工具实例必须在注入完成后创建
        tool = new WebSearchToolCallback(webSearchService);
    }

    @Test
    @DisplayName("成功命中：结果透传 + provider 从供应商取，withAnswer=false 不调总结")
    void shouldSearchAndReturnResults() {
        when(webSearchService.provider()).thenReturn("bocha");
        when(webSearchService.search(eq("报表"), eq(5))).thenReturn(List.of(
                WebSearchResult.builder().title("报表方案")
                        .url("https://a.example/1").snippet("摘要").build()));

        WebSearchToolResult out = tool.webSearch("报表", 5, false);

        assertThat(out.provider()).isEqualTo("bocha");
        assertThat(out.results()).hasSize(1);
        assertThat(out.results().get(0).getTitle()).isEqualTo("报表方案");
        assertThat(out.answer()).isNull();
        verify(webSearchService).search("报表", 5);
        verify(webSearchService, never()).answerSummary(anyString());
    }

    @Test
    @DisplayName("空关键词：返回空结果且不发起搜索（业务开关之外的能力层防御）")
    void shouldReturnEmptyForBlankQuery() {
        WebSearchToolResult out = tool.webSearch("   ", 5, false);

        assertThat(out.provider()).isEqualTo("web_search_tool");
        assertThat(out.results()).isEmpty();
        verify(webSearchService, never()).search(anyString(), eq(5));
    }

    @Test
    @DisplayName("maxResults clamp：null/0 落默认 3，超 10 收敛 10")
    void shouldClampMaxResults() {
        when(webSearchService.search(eq("词一"), eq(3))).thenReturn(List.of());
        when(webSearchService.search(eq("词二"), eq(3))).thenReturn(List.of());
        when(webSearchService.search(eq("词三"), eq(10))).thenReturn(List.of());

        tool.webSearch("词一", null, false);
        tool.webSearch("词二", 0, false);
        tool.webSearch("词三", 100, false);

        verify(webSearchService).search("词一", 3);
        verify(webSearchService).search("词二", 3);
        verify(webSearchService).search("词三", 10);
    }

    @Test
    @DisplayName("搜索异常：降级空结果不抛（消费方按空列表处理，绝不阻塞）")
    void shouldDegradeToEmptyOnSearchFailure() {
        when(webSearchService.search(anyString(), eq(3))).thenThrow(new RuntimeException("bocha timeout"));

        WebSearchToolResult out = tool.webSearch("报表", null, false);

        assertThat(out.provider()).isEqualTo("web_search_tool");
        assertThat(out.results()).isEmpty();
    }

    @Test
    @DisplayName("withAnswer=true：请求供应商大模型总结并填充 answer")
    void shouldFetchAnswerWhenRequested() {
        when(webSearchService.search(eq("报表"), eq(3))).thenReturn(List.of());
        when(webSearchService.answerSummary("报表")).thenReturn("报表应关注趋势");

        WebSearchToolResult out = tool.webSearch("报表", null, true);

        assertThat(out.answer()).isEqualTo("报表应关注趋势");
        verify(webSearchService).answerSummary("报表");
    }

    @Test
    @DisplayName("toolObject 返回自身：agent 域收集器合并原始对象（代理会丢 @Tool 注解）")
    void toolObjectReturnsSelf() {
        assertThat(tool.toolObject()).isSameAs(tool);
    }

    @Test
    @DisplayName("WebSearchToolResult 构造器：null 字段规范化为默认值，empty 工厂幂等")
    void shouldNormalizeNullFieldsInResult() {
        WebSearchToolResult normalized = new WebSearchToolResult(null, null, null);
        assertThat(normalized.provider()).isEmpty();
        assertThat(normalized.results()).isEmpty();
        assertThat(normalized.answer()).isNull();

        WebSearchToolResult empty = WebSearchToolResult.empty("web_search_tool");
        assertThat(empty.provider()).isEqualTo("web_search_tool");
        assertThat(empty.results()).isEmpty();
    }
}