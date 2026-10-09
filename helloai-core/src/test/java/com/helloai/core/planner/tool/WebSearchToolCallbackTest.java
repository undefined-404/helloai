package com.helloai.core.planner.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.tool.ToolContext;
import com.helloai.core.planner.search.WebSearchResult;
import com.helloai.core.planner.service.WebSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

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
    @DisplayName("工具注册名：必须为 web_search（编排层按名调用 + 技能包 requiredTools 对齐，防驼峰漂移）")
    void toolRegistrationNameShouldBeWebSearch() {
        // 直调用例全覆盖不到注册名——只有真实构建 spring-ai 工具目录才能暴露命名漂移
        MethodToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(tool)
                .build();

        assertThat(provider.getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .as("编排层按 web_search 调用（ClarifyWebSearchOrchestrator），注册名必须一致")
                .containsExactly("web_search");
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

    @Test
    @DisplayName("目录端到端往返：注册名可命中 + 工具输出 JSON 可被编排层反序列化（成功路径收口）")
    void shouldRoundTripThroughRealCatalog() throws Exception {
        // 修复前 unknown tool 导致服务端成功路径从未真实跑通；
        // 本用例用真实 spring-ai 目录调用 + 裸 ObjectMapper 回读，模拟编排层取数口径
        when(webSearchService.provider()).thenReturn("bocha-ai-search");
        when(webSearchService.search(eq("新闻"), eq(3))).thenReturn(List.of(
                WebSearchResult.builder().title("头条").url("https://n.example/1")
                        .snippet("摘要").siteName("示例站").build()));

        ToolCallback callback = MethodToolCallbackProvider.builder()
                .toolObjects(tool)
                .build()
                .getToolCallbacks()[0];

        String output = callback.call("{\"query\":\"新闻\",\"maxResults\":3}");

        WebSearchToolResult parsed = new ObjectMapper().readValue(output, WebSearchToolResult.class);
        assertThat(parsed.provider()).isEqualTo("bocha-ai-search");
        assertThat(parsed.results()).hasSize(1);
        assertThat(parsed.results().get(0).getUrl()).isEqualTo("https://n.example/1");
        assertThat(parsed.results().get(0).getSiteName()).isEqualTo("示例站");
    }

    // #region REF-1.3 条件可用（首个真实消费者）

    @Test
    @DisplayName("★ REF-1.3：条件可用声明的键必须与真实注册名一致（防键名漂移导致声明静默失效）")
    void availabilityKeyShouldMatchRegisteredToolName() {
        String registeredName = MethodToolCallbackProvider.builder()
                .toolObjects(tool)
                .build()
                .getToolCallbacks()[0]
                .getToolDefinition()
                .name();

        assertThat(tool.toolAvailability()).containsOnlyKeys(registeredName);
    }

    @Test
    @DisplayName("REF-1.3 条件可用：能力不具备（无凭据 / 总开关关闭）⇒ 判为不可用（模型视野摘除）")
    void shouldDeclareUnavailableWhenSearchCapabilityMissing() {
        when(webSearchService.isAvailable()).thenReturn(false);

        assertThat(availability(ToolContext.empty())).isFalse();
    }

    @Test
    @DisplayName("REF-1.3 条件可用：能力具备 ⇒ 判为可用；判定不依赖 ToolContext 任何字段（天然 fail-open）")
    void shouldDeclareAvailableWhenSearchCapabilityReady() {
        when(webSearchService.isAvailable()).thenReturn(true);

        assertThat(availability(ToolContext.empty())).isTrue();
        assertThat(availability(new ToolContext(1L, 2L, 3L, 5,
                AgentAccessType.API_KEY_LLM, List.of("eng-web-research")))).isTrue();
    }

    private boolean availability(ToolContext context) {
        return tool.toolAvailability().get("web_search").test(context);
    }

    // #endregion
}