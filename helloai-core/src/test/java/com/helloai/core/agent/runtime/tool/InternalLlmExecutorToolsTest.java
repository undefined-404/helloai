package com.helloai.core.agent.runtime.tool;

import com.helloai.common.base.BizException;
import com.helloai.core.agent.port.AttachmentPort;
import com.helloai.core.agent.runtime.sandbox.ExecuteResponse;
import com.helloai.core.agent.runtime.sandbox.SandboxExecutionService;
import com.helloai.core.agent.service.McpToolService;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.agent.skill.SkillPackage;
import com.helloai.core.agent.tool.ToolInvocationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link InternalLlmExecutorTools} 单测（REF-8.1 M1 / REF-8.3 M3）。
 *
 * <p>验证进程内 {@code @Tool} 的委托正确性：读型工具复用 {@link McpToolService}
 * 同名方法；交付登记 / 附件读取分别走 {@link AttachmentPort} 的 {{@code register}} / {code loadContent}；
 * 技能工具（REF-8.3）走 {@link AgentSkillSpecService} 的 {code listPackages} / {code loadBody}。
 * 均为进程内直连，无 {@code McpMcpServer} / {@code McpAuthContext} session 鉴权依赖。</p>
 */
@DisplayName("InternalLlmExecutorTools 进程内工具通道（REF-8.1 / REF-8.3）")
class InternalLlmExecutorToolsTest {

    private final McpToolService mcpToolService = mock(McpToolService.class);
    private final AttachmentPort attachmentPort = mock(AttachmentPort.class);
    private final AgentSkillSpecService agentSkillSpecService = mock(AgentSkillSpecService.class);
    private final com.helloai.core.agent.port.SubTaskCommandPort subTaskCommandPort =
            mock(com.helloai.core.agent.port.SubTaskCommandPort.class);
    private final SandboxExecutionService sandboxExecutionService = mock(SandboxExecutionService.class);
    private final InternalLlmExecutorTools tools =
            new InternalLlmExecutorTools(mcpToolService, attachmentPort, agentSkillSpecService,
                    subTaskCommandPort, sandboxExecutionService);

    /** REF-8.1 A1 强制闸门：本类工具仅可由内部执行链调用，单测需先绑定执行链上下文。 */
    @BeforeEach
    void bindInternalChain() {
        ToolInvocationContext.bind(11L, 22L);
    }

    @AfterEach
    void unbindInternalChain() {
        ToolInvocationContext.unbind();
    }

    @Test
    @DisplayName("REF-8.1 A1 闸门：未绑定执行链上下文时一律拒绝（外部越权不可达）")
    void shouldRejectWhenUnbound() {
        ToolInvocationContext.unbind();

        assertThatThrownBy(() -> tools.internalGetSubTaskDetail(11L, 22L))
                .isInstanceOf(BizException.class).hasMessageContaining("仅可由平台内部执行链调用");
        assertThatThrownBy(() -> tools.internalReadAttachment(11L, 50L))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(tools::internalListSkills)
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> tools.internalRegisterOutputAttachment(
                11L, 22L, "x.md", "text/markdown", 1L, "minio://b/x.md"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> tools.internalRunInSandbox(11L, 22L, "echo ok", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅可由平台内部执行链调用");
    }

    @Test
    @DisplayName("REF-8.1 A1 闸门：使用绑定的 agentId/subTaskId，忽略调用方传入值（不可越权冒充）")
    void shouldUseBoundIdsNotCallerSupplied() {
        McpToolService.SubTaskDetail detail = new McpToolService.SubTaskDetail();
        // 绑定值为 (11,22)；调用方冒充 (999,888) 也必须落到绑定值
        when(mcpToolService.getSubTaskDetail(11L, 22L)).thenReturn(detail);

        Object result = tools.internalGetSubTaskDetail(999L, 888L);

        assertThat(result).isSameAs(detail);
        verify(mcpToolService).getSubTaskDetail(11L, 22L);
    }

    @Test
    @DisplayName("internal_get_sub_task_detail：委托 McpToolService.getSubTaskDetail（进程内直连）")
    void shouldDelegateGetSubTaskDetail() {
        McpToolService.SubTaskDetail detail = new McpToolService.SubTaskDetail();
        detail.setSubTaskId(22L);
        detail.setTaskId(1L);
        detail.setTitle("t");
        // 依赖注入事例可空：这里只验证委托透传
        when(mcpToolService.getSubTaskDetail(11L, 22L)).thenReturn(detail);

        Object result = tools.internalGetSubTaskDetail(11L, 22L);

        assertThat(result).isSameAs(detail);
        verify(mcpToolService).getSubTaskDetail(11L, 22L);
    }

    @Test
    @DisplayName("internal_get_deps_summary：委托 McpToolService.getDepsSummary")
    void shouldDelegateGetDepsSummary() {
        McpToolService.GetDepsSummaryResult summary = new McpToolService.GetDepsSummaryResult();
        summary.setSubTaskId(22L);
        summary.setDepCount(1);
        summary.setReady(true);
        when(mcpToolService.getDepsSummary(11L, 22L)).thenReturn(summary);

        Object result = tools.internalGetDepsSummary(11L, 22L);

        assertThat(result).isSameAs(summary);
        verify(mcpToolService).getDepsSummary(11L, 22L);
    }

    @Test
    @DisplayName("internal_get_agent_status：委托 McpToolService.getAgentStatus")
    void shouldDelegateGetAgentStatus() {
        McpToolService.GetAgentStatusResult status = new McpToolService.GetAgentStatusResult();
        status.setAgentId(11L);
        status.setStatus("ACTIVE");
        when(mcpToolService.getAgentStatus(11L)).thenReturn(status);

        Object result = tools.internalGetAgentStatus(11L);

        assertThat(result).isSameAs(status);
        verify(mcpToolService).getAgentStatus(11L);
    }

    @Test
    @DisplayName("internal_register_output_attachment：走 AttachmentPort.register，不触发状态流转")
    void shouldRegisterOutputAttachment() {
        when(attachmentPort.register(11L, 22L, "out.md", "text/markdown", 10L, "minio://bucket/out.md"))
                .thenReturn(99L);

        Object result = tools.internalRegisterOutputAttachment(
                11L, 22L, "out.md", "text/markdown", 10L, "minio://bucket/out.md");

        assertThat(result).isEqualTo(Map.of("attachmentId", 99L, "subTaskId", 22L));
        verify(attachmentPort).register(11L, 22L, "out.md", "text/markdown", 10L, "minio://bucket/out.md");
    }

    @Test
    @DisplayName("internal_register_output_attachment：mimeType / fileSize 空时回退默认值")
    void shouldRegisterOutputAttachmentWithDefaults() {
        when(attachmentPort.register(11L, 22L, "out.bin",
                "application/octet-stream", 0L, "minio://bucket/a.bin")).thenReturn(5L);

        tools.internalRegisterOutputAttachment(11L, 22L, "out.bin", null, null, "minio://bucket/a.bin");

        verify(attachmentPort).register(11L, 22L, "out.bin",
                "application/octet-stream", 0L, "minio://bucket/a.bin");
    }

    @Test
    @DisplayName("internal_read_attachment：走 AttachmentPort.loadContent 并 UTF-8 解码")
    void shouldReadAttachment() {
        String content = "附件正文";
        when(attachmentPort.loadContent(50L)).thenReturn(content.getBytes(StandardCharsets.UTF_8));

        Object result = tools.internalReadAttachment(11L, 50L);

        assertThat(result).isEqualTo(content);
        verify(attachmentPort).loadContent(50L);
    }

    @Test
    @DisplayName("REF-8.3 internal_list_skills：返回全量技能目录（name/version/description/requiredTools）")
    void shouldListSkills() {
        SkillPackage pkg = new SkillPackage("eng-code-review", "1.0.0", "代码评审规范",
                List.of("search_knowledge"), List.of(), List.of(), Map.of(), Map.of(), List.of(), "eng-code-review.md");
        when(agentSkillSpecService.listPackages()).thenReturn(List.of(pkg));

        Object result = tools.internalListSkills();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) result;
        assertThat(list).hasSize(1);
        assertThat(list.get(0))
                .containsEntry("name", "eng-code-review")
                .containsEntry("version", "1.0.0")
                .containsEntry("requiredTools", List.of("search_knowledge"));
        verify(agentSkillSpecService).listPackages();
    }

    @Test
    @DisplayName("REF-8.3 internal_load_skill：惰性加载指定技能正文（走 AgentSkillSpecService.loadBody）")
    void shouldLoadSkillBody() {
        when(agentSkillSpecService.loadBody("eng-code-review")).thenReturn("## 代码评审规范\n正文……");

        Object result = tools.internalLoadSkill("eng-code-review");

        assertThat(result).isEqualTo("## 代码评审规范\n正文……");
        verify(agentSkillSpecService).loadBody("eng-code-review");
    }

    // ────────────────────────────────────────────────────────────
    //  REF-8.1.1：自报阻塞（「做不到」的结构化通道）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("REF-8.1.1 知情拒绝闸门：未读过任务详情即报阻塞 → 拒绝（不落任何状态）")
    void shouldRejectBlockedBeforeReadingDetail() {
        assertThatThrownBy(() -> tools.internalReportBlocked(11L, "缺少原始数据文件，无法核对字段"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("必须先调用 internal_get_sub_task_detail");
        verify(subTaskCommandPort, org.mockito.Mockito.never())
                .block(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("REF-8.1.1 原因过短（敷衍）→ 拒绝")
    void shouldRejectTooShortReason() {
        when(mcpToolService.getSubTaskDetail(11L, 22L)).thenReturn(new McpToolService.SubTaskDetail());
        tools.internalGetSubTaskDetail(11L, 22L);   // 满足知情闸门

        assertThatThrownBy(() -> tools.internalReportBlocked(11L, "做不了"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("阻塞原因过短");
    }

    @Test
    @DisplayName("REF-8.1.1 知情后报阻塞 → 子任务转 BLOCKED + 标记人工介入（不再进评审）")
    void shouldBlockSubTaskAfterReadingDetail() {
        when(mcpToolService.getSubTaskDetail(11L, 22L)).thenReturn(new McpToolService.SubTaskDetail());
        tools.internalGetSubTaskDetail(11L, 22L);

        Object result = tools.internalReportBlocked(11L, "缺少用户导出的原始数据文件，平台可读附件内无该文件，无法核对字段");

        verify(subTaskCommandPort).block(22L,
                "缺少用户导出的原始数据文件，平台可读附件内无该文件，无法核对字段", 11L);
        verify(subTaskCommandPort).markManualIntervention(
                org.mockito.ArgumentMatchers.eq(22L),
                org.mockito.ArgumentMatchers.eq("executor_reported_blocked"),
                org.mockito.ArgumentMatchers.anyMap());
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map).containsEntry("status", "BLOCKED").containsEntry("subTaskId", 22L);
    }

    @Test
    @DisplayName("REF-8.1.1 闸门随执行链：解绑后调用记录清零（不跨链串味）")
    void shouldClearCalledMarkersOnUnbind() {
        when(mcpToolService.getSubTaskDetail(11L, 22L)).thenReturn(new McpToolService.SubTaskDetail());
        tools.internalGetSubTaskDetail(11L, 22L);
        assertThat(ToolInvocationContext.hasCalled("internal_get_sub_task_detail")).isTrue();

        ToolInvocationContext.unbind();
        assertThat(ToolInvocationContext.hasCalled("internal_get_sub_task_detail")).isFalse();
        ToolInvocationContext.bind(11L, 22L);   // 复位供 @AfterEach/#后续用例
    }

    // ────────────────────────────────────────────────────────────
    //  S-8：沙箱执行通道（internal_run_in_sandbox）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S-8 internal_run_in_sandbox：绑定执行链下委托 SandboxExecutionService.execute（用绑定 subTaskId）")
    void shouldRunInSandboxWithBoundSubTaskId() {
        ExecuteResponse response = new ExecuteResponse("out", 0, false);
        when(sandboxExecutionService.execute(22L, "python -c 'print(1)'", 30)).thenReturn(response);

        Object result = tools.internalRunInSandbox(999L, 888L, "python -c 'print(1)'", 30);

        // 调用方冒充 (999,888) 也必须落到绑定值 (11,22) → subTaskId=22
        assertThat(result).isSameAs(response);
        verify(sandboxExecutionService).execute(22L, "python -c 'print(1)'", 30);
    }

    @Test
    @DisplayName("S-8 internal_run_in_sandbox：timeoutSeconds 为空时透传 null（由执行服务按默认 30 处理）")
    void shouldPassNullTimeoutToExecutionService() {
        when(sandboxExecutionService.execute(22L, "echo ok", null))
                .thenReturn(new ExecuteResponse("ok", 0, false));

        tools.internalRunInSandbox(11L, 22L, "echo ok", null);

        verify(sandboxExecutionService).execute(22L, "echo ok", null);
    }

    @Test
    @DisplayName("S-8 internal_run_in_sandbox：执行失败/超时也结构化返回（不抛异常，交给模型判定）")
    void shouldReturnStructuredFailureFromSandbox() {
        ExecuteResponse failed = new ExecuteResponse("sandbox execution failed: ConnectException: x",
                ExecuteResponse.EXIT_CODE_ERROR, false);
        when(sandboxExecutionService.execute(22L, "cat nope.txt", 30)).thenReturn(failed);

        Object result = tools.internalRunInSandbox(11L, 22L, "cat nope.txt", 30);

        assertThat(result).isSameAs(failed);
        verify(sandboxExecutionService).execute(22L, "cat nope.txt", 30);
    }
}