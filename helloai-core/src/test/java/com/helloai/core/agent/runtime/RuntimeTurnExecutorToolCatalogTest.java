package com.helloai.core.agent.runtime;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentEventType;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.runtime.loop.AgentLoop;
import com.helloai.core.agent.runtime.loop.AgentLoopInput;
import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import com.helloai.core.agent.runtime.sandbox.SandboxContext;
import com.helloai.core.agent.runtime.sandbox.SandboxProvider;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.agent.tool.ToolDefinition;
import com.helloai.core.agent.tool.ToolExecutor;
import com.helloai.core.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MA-5 第一步「目录 + 按需取」的接线测试：{@link RuntimeTurnExecutor} 把
 * {@link ToolRegistry#resolve} 的生效结果经 {@code ToolCatalogAssembler} 装配后才
 * 交给模型可见回调——长描述被摘要、白名单保留全文、短描述原样；同时钉死
 * <b>对外零回归</b>：名字 / 顺序 / 数量不变（effectiveTools / removedTools 语义零变化），
 * TOOL_RESOLVED 事件记录的是 resolve 的<b>原始完整描述</b>（装配只作用模型可见面）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RuntimeTurnExecutor × ToolCatalogAssembler 接线（MA-5 第一步）")
class RuntimeTurnExecutorToolCatalogTest {

    @Mock
    private AgentLoop agentLoop;

    @Mock
    private ToolExecutor toolExecutor;

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private AgentSkillSpecService agentSkillSpecService;

    @Mock
    private SandboxProvider sandboxProvider;

    @Mock
    private ToolCallbackProvider toolCallbackProvider;

    @Mock
    private ChatModel chatModel;

    @Mock
    private AgentEventRecorder eventRecorder;

    /** 超长描述（真实形态：含空格与标点，避免触发极端词边界回退的边界行为）。 */
    private static final String LONG_DESC =
            "【何时使用】需要执行一段命令或脚本以验证产出时调用——自检：验证脚本跑通、命令输出与结论一致；"
                    + "Reviewer：验证脚本确实能通过目标验收标准。返回统一执行结果 {output, exitCode, truncated}。"
                    + " 默认禁网、零平台凭据、文件系统受隔离；输出超 100KB 会被截断并标注 truncated。"
                    + " 沙箱未启用时本工具不会出现（fail-closed，不静默降级）。";

    private static final String DEFAULT_INPUT_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    private record FakeDefinition(String name, String description, String inputSchema)
            implements org.springframework.ai.tool.definition.ToolDefinition {
    }

    private record FakeCallback(org.springframework.ai.tool.definition.ToolDefinition definition)
            implements ToolCallback {
        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            return "";
        }
    }

    private ToolCallback callback(String name, String description) {
        return new FakeCallback(new FakeDefinition(name, description, DEFAULT_INPUT_SCHEMA));
    }

    private void stubRun() {
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of("s1"), List.of("s1"), ""));
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop("ok", null, 1, 0));
    }

    private void execute(List<ToolDefinition> resolved, ToolCallback... callbacks) {
        when(toolRegistry.resolve(any(), any())).thenReturn(resolved);
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(
                callbacks == null ? new ToolCallback[0] : callbacks);
        stubRun();
        // 6 参显式构造：toolCatalogProperties 字段为 null ⇒ 回落 new ToolCatalogProperties()
        // （160 摘要 + 4 个白名单工具），行为确定，正好验证默认配置下的装配。
        new RuntimeTurnExecutor(agentLoop, toolExecutor, toolRegistry, agentSkillSpecService,
                sandboxProvider, toolCallbackProvider).execute(context());
    }

    private AgentLoopInput captureLoopInput() {
        ArgumentCaptor<AgentLoopInput> captor = ArgumentCaptor.forClass(AgentLoopInput.class);
        verify(agentLoop).run(captor.capture());
        return captor.getValue();
    }

    private AgentContext context() {
        return AgentContext.builder()
                .runId("run-1-1").taskId(1L).subTaskId(10L).turn(1).step(0).agentId(3L)
                .skills(List.of("s1")).tools(List.of("t1"))
                .environment(new LocalProcessEnvironment())
                .accessType(AgentAccessType.API_KEY_LLM)
                .systemPrompt("sys").userPrompt("user")
                .chatModel(chatModel)
                .eventRecorder(eventRecorder)
                .build();
    }

    @Test
    @DisplayName("长描述非白名单 ⇒ 模型可见描述被摘要（≤160 码点 + 省略标记），name 不变")
    void shouldSummarizeLongDescriptionForModelVisibleCallbacks() {
        execute(List.of(new ToolDefinition("internal_custom_tool", LONG_DESC)),
                callback("internal_custom_tool", "旧描述"));

        List<ToolCallback> visible = captureLoopInput().enabledToolCallbacks();
        assertThat(visible).hasSize(1);
        String output = visible.get(0).getToolDefinition().description();
        assertThat(output).endsWith("…");
        assertThat(output.codePointCount(0, output.length())).isLessThanOrEqualTo(161);
        assertThat(visible.get(0).getToolDefinition().name()).isEqualTo("internal_custom_tool");
    }

    @Test
    @DisplayName("白名单工具（默认 4 个高频核心）⇒ 模型可见描述保留完整全文")
    void shouldKeepWhitelistedToolFullDescription() {
        String whitelisted = "x".repeat(500);
        execute(List.of(new ToolDefinition("internal_run_in_sandbox", whitelisted)),
                callback("internal_run_in_sandbox", "旧描述"));

        List<ToolCallback> visible = captureLoopInput().enabledToolCallbacks();
        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).getToolDefinition().description()).isEqualTo(whitelisted);
    }

    @Test
    @DisplayName("短描述（≤160）⇒ 原样透传，不追加省略标记")
    void shouldKeepShortDescriptionUntouched() {
        execute(List.of(new ToolDefinition("web_search", "联网搜索")),
                callback("web_search", "联网搜索"));

        List<ToolCallback> visible = captureLoopInput().enabledToolCallbacks();
        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).getToolDefinition().description()).isEqualTo("联网搜索");
    }

    @Test
    @DisplayName("对外零改动：装配只换 description 内容，名字 / 顺序 / 数量逐字不变（effectiveTools 语义）")
    void shouldKeepNamesOrderAndCountUnchanged() {
        execute(List.of(
                new ToolDefinition("internal_a", "short"),
                new ToolDefinition("internal_b", LONG_DESC),
                new ToolDefinition("internal_c", "also short")),
                callback("internal_a", "a"),
                callback("internal_b", "b"),
                callback("internal_c", "c"));

        List<ToolCallback> visible = captureLoopInput().enabledToolCallbacks();
        assertThat(visible).hasSize(3);
        assertThat(visible.stream().map(cb -> cb.getToolDefinition().name()).toList())
                .containsExactly("internal_a", "internal_b", "internal_c");
    }

    @Test
    @DisplayName("TOOL_RESOLVED 事件零回归：resolvedTools 记录 resolve 的原始完整描述（装配只作用模型可见面）")
    void shouldRecordOriginalDescriptionInToolResolvedEvent() {
        execute(List.of(new ToolDefinition("internal_custom_tool", LONG_DESC)),
                callback("internal_custom_tool", "旧描述"));

        ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventRecorder).record(org.mockito.ArgumentMatchers.eq("run-1-1"),
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq(6),
                org.mockito.ArgumentMatchers.eq(AgentEventType.TOOL_RESOLVED),
                org.mockito.ArgumentMatchers.eq(3L), payloadCaptor.capture());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resolvedTools = (List<Map<String, Object>>) payloadCaptor.getValue()
                .get("resolvedTools");
        assertThat(resolvedTools).hasSize(1);
        // 事件里是 resolve 的原始输出（完整描述），不是装配后的摘要——外部读取零回归
        assertThat(resolvedTools.get(0).get("description")).isEqualTo(LONG_DESC);
        // effectiveTools 仍按名字（与装配前逐字一致）
        assertThat(payloadCaptor.getValue().get("effectiveTools")).isEqualTo(List.of("internal_custom_tool"));
    }

    @Test
    @DisplayName("resolve 落空（条件摘除 / 未知）⇒ 模型可见回调为空（目录字段不幻影）")
    void shouldKeepEmptyWhenResolveEmpty() {
        execute(List.of(), callback("internal_custom_tool", "不该出现"));

        List<ToolCallback> visible = captureLoopInput().enabledToolCallbacks();
        assertThat(visible).isEmpty();
    }
}