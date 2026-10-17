package com.helloai.core.agent.mcp;

import com.helloai.core.agent.runtime.sandbox.SandboxExecutionToolContributor;
import com.helloai.core.agent.runtime.tool.InternalLlmExecutorTools;
import com.helloai.core.agent.service.McpToolService;
import com.helloai.core.agent.tool.InternalToolCallbacks;
import com.helloai.core.agent.tool.ToolCallbackContributor;
import com.helloai.core.agent.tool.ToolContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * {@link McpToolConfig} 工具面装配单测（S-8 回归，LOG-20261017-016）。
 *
 * <p><b>背景</b>：启动后端报 {@code No @Tool annotated methods found in
 * SandboxExecutionToolContributor}——纯声明类（{@code toolObject()} 返回 null，工具本体
 * 在 {@code InternalLlmExecutorTools}）被收集进对外 MCP 面，spring-ai 扫描无 {@code @Tool}
 * 方法即抛异常。修复 = 收集器过滤 {@code null}（{@link ToolCallbackContributor#toolObject()}
 * 默认返回 null，纯声明类返回 null，{@code mcpToolCallbacks()} 过滤）。
 *
 * <p><b>验证点</b>（性质断言，不猜数量——真实类 @Tool 数随迭代漂移）：
 * ① 纯声明 contributor（{@code toolObject()==null}）混入时装配不炸（本 bug 的直接回归）；
 * ② 对外面不含纯声明类的工具（{@code internal_run_in_sandbox} 不进对外面，REF-8.1 B 红线）；
 * ③ 工具本体 contributor 的 {@code @Tool} 并入对外面；
 * ④ 对外面不含任何 {@code internal_} 前缀工具（内部执行链专用，不对外）。</p>
 */
@DisplayName("McpToolConfig 工具面装配（S-8 纯声明类过滤回归）")
class McpToolConfigTest {

    private final EchoMcpTool echoMcpTool = new EchoMcpTool();
    /** 真实 McpMcpServer（mock 其依赖）——@Tool 方法经反射扫描，mock 对象会扫到父类 @Tool，故用真实类。 */
    private final McpMcpServer mcpMcpServer =
            new McpMcpServer(mock(McpToolService.class), mock(McpAuthContext.class));
    /** mock 的 InternalLlmExecutorTools：反射扫描会扫到父类全部 @Tool（internal_* 9 个）——正好验证「内部面独立承载」。 */
    private final InternalLlmExecutorTools internalTools = mock(InternalLlmExecutorTools.class);

    /** 纯声明 contributor：toolObject()=null（默认实现），只声明 internal_run_in_sandbox 可用。 */
    private ToolCallbackContributor pureDeclaringContributor() {
        return new ToolCallbackContributor() {
            @Override
            public Map<String, Predicate<ToolContext>> toolAvailability() {
                return Map.of(SandboxExecutionToolContributor.TOOL_INTERNAL_RUN_IN_SANDBOX, ctx -> true);
            }
        };
    }

    /** 工具本体 contributor：返回含 {@code @Tool} 方法的独立实例（模拟 WebSearchToolCallback）。 */
    private ToolCallbackContributor toolBodyContributor() {
        return new ToolCallbackContributor() {
            @Override
            public Object toolObject() {
                return new FakeBodyTool();
            }
        };
    }

    /** 独立工具本体（专测「工具本体 contributor 被并入对外面」）。 */
    static class FakeBodyTool {
        @Tool(description = "fake body tool for contributor merge test")
        public String fakeBody(@ToolParam(description = "arg") String arg) {
            return arg;
        }
    }

    private McpToolConfig config(List<ToolCallbackContributor> contributors) {
        return new McpToolConfig(echoMcpTool, mcpMcpServer, internalTools, contributors);
    }

    @Test
    @DisplayName("纯声明 contributor 混入时，对外 MCP 面装配不炸（回归：No @Tool annotated methods found）")
    void shouldNotFailWhenPureDeclaringContributorPresent() {
        McpToolConfig config = config(List.of(pureDeclaringContributor()));
        assertThatCode(config::mcpToolCallbacks).doesNotThrowAnyException();

        // 对外面不含 internal_run_in_sandbox（纯声明类被过滤，REF-8.1 B 红线）
        assertThat(toolNames(config.mcpToolCallbacks()))
                .doesNotContain(SandboxExecutionToolContributor.TOOL_INTERNAL_RUN_IN_SANDBOX);
    }

    @Test
    @DisplayName("对外面并入工具本体 contributor 的 @Tool，且不含纯声明类的工具")
    void shouldIncludeToolBodyContributorAndExcludePureDeclaring() {
        McpToolConfig config = config(List.of(pureDeclaringContributor(), toolBodyContributor()));
        assertThatCode(config::mcpToolCallbacks).doesNotThrowAnyException();

        Set<String> names = toolNames(config.mcpToolCallbacks());
        assertThat(names).contains("echo", "fakeBody");                   // 基线 + 工具本体并入
        assertThat(names).doesNotContain("internal_run_in_sandbox");      // 纯声明类被过滤
    }

    @Test
    @DisplayName("contributor 列表为 null/空时，装配不炸且不含纯声明类工具（零回归）")
    void shouldHandleNullAndEmptyContributors() {
        McpToolConfig withNull = config(null);
        assertThatCode(withNull::mcpToolCallbacks).doesNotThrowAnyException();
        assertThat(toolNames(withNull.mcpToolCallbacks()))
                .doesNotContain("internal_run_in_sandbox");

        McpToolConfig withEmpty = config(List.of());
        assertThatCode(withEmpty::mcpToolCallbacks).doesNotThrowAnyException();
        assertThat(toolNames(withEmpty.mcpToolCallbacks()))
                .doesNotContain("internal_run_in_sandbox");
    }

    @Test
    @DisplayName("对外面含 echo + mcp 基线工具；内部面独立承载 internal_*，两者互不污染（REF-8.1 B）")
    void shouldKeepInternalToolsOutOfExternalFace() {
        McpToolConfig config = config(List.of(pureDeclaringContributor()));

        // 对外面：echo + mcp 基线在，internal_* 一个不在
        Set<String> external = toolNames(config.mcpToolCallbacks());
        assertThat(external).contains("echo", "pullTasks", "ack", "claimSubTask", "heartbeat",
                "uploadArtifact", "submitResult", "reportBlocked", "getAgentStatus",
                "getDepsSummary", "getSubTaskDetail");
        for (String name : external) {
            assertThat(name).doesNotStartWith("internal_");
        }

        // 内部面：mock 父类 @Tool 被扫到 ⇒ 含 internal_* 工具（验证内部面独立承载，不进对外面）
        InternalToolCallbacks internal = config.internalToolCallbacks();
        Set<String> internalNames = new HashSet<>();
        for (ToolCallback cb : internal.getToolCallbacks()) {
            internalNames.add(cb.getToolDefinition().name());
        }
        assertThat(internalNames).isNotEmpty();
        assertThat(internalNames).noneMatch(n -> !n.startsWith("internal_"));
        // 两面交集为空（REF-8.1 B 红线：内部工具不对外）
        assertThat(internalNames).doesNotContainAnyElementsOf(external);
    }

    private static Set<String> toolNames(ToolCallbackProvider provider) {
        Set<String> names = new HashSet<>();
        for (ToolCallback cb : provider.getToolCallbacks()) {
            names.add(cb.getToolDefinition().name());
        }
        return names;
    }
}
