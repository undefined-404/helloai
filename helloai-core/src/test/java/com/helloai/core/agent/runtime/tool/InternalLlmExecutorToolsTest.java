package com.helloai.core.agent.runtime.tool;

import com.helloai.common.base.BizException;
import com.helloai.core.agent.port.AttachmentPort;
import com.helloai.core.agent.runtime.sandbox.ExecuteResponse;
import com.helloai.core.agent.runtime.sandbox.SandboxArtifact;
import com.helloai.core.agent.runtime.sandbox.SandboxCallOrigin;
import com.helloai.core.agent.runtime.sandbox.SandboxExecutionService;
import com.helloai.core.agent.runtime.sandbox.SkillInstallResult;
import com.helloai.core.agent.runtime.sandbox.impl.SkillExecutionSupport;
import com.helloai.core.agent.service.McpToolService;
import com.helloai.core.agent.service.ConversationService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private final com.helloai.core.agent.runtime.sandbox.impl.SkillExecutionSupport skillExecutionSupport =
            mock(com.helloai.core.agent.runtime.sandbox.impl.SkillExecutionSupport.class);
    private final com.helloai.core.agent.service.SkillArtifactExportService skillArtifactExportService =
            mock(com.helloai.core.agent.service.SkillArtifactExportService.class);
    private final ConversationService conversationService = mock(ConversationService.class);
    private final com.helloai.core.agent.runtime.sandbox.SandboxCallLedger sandboxCallLedger =
            new com.helloai.core.agent.runtime.sandbox.SandboxCallLedger();
    private final InternalLlmExecutorTools tools =
            new InternalLlmExecutorTools(mcpToolService, attachmentPort, agentSkillSpecService,
                    subTaskCommandPort, sandboxExecutionService, skillExecutionSupport,
                    skillArtifactExportService, conversationService, sandboxCallLedger);

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
        // LOG-20261112：自报阻塞补对话结论，避免执行轮只挂请求行悬空。
        verify(conversationService).addMessage(
                org.mockito.ArgumentMatchers.eq(22L),
                org.mockito.ArgumentMatchers.eq(11L),
                org.mockito.ArgumentMatchers.eq("assistant"),
                org.mockito.ArgumentMatchers.eq("agent"),
                org.mockito.ArgumentMatchers.contains("执行阻塞"),
                org.mockito.ArgumentMatchers.eq("sub_task_execute_blocked"));
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
        when(sandboxExecutionService.execute(22L, null, "python -c 'print(1)'", 30,
                SandboxCallOrigin.EXECUTOR, 0)).thenReturn(response);

        Object result = tools.internalRunInSandbox(999L, 888L, "python -c 'print(1)'", 30);

        // 调用方冒充 (999,888) 也必须落到绑定值 (11,22) → subTaskId=22
        assertThat(result).isSameAs(response);
        verify(sandboxExecutionService).execute(22L, null, "python -c 'print(1)'", 30,
                SandboxCallOrigin.EXECUTOR, 0);
    }

    @Test
    @DisplayName("S-8 internal_run_in_sandbox：timeoutSeconds 为空时透传 null（由执行服务按默认 30 处理）")
    void shouldPassNullTimeoutToExecutionService() {
        when(sandboxExecutionService.execute(22L, null, "echo ok", null,
                SandboxCallOrigin.EXECUTOR, 0))
                .thenReturn(new ExecuteResponse("ok", 0, false));

        tools.internalRunInSandbox(11L, 22L, "echo ok", null);

        verify(sandboxExecutionService).execute(22L, null, "echo ok", null,
                SandboxCallOrigin.EXECUTOR, 0);
    }

    @Test
    @DisplayName("S-8 internal_run_in_sandbox：执行失败/超时也结构化返回（不抛异常，交给模型判定）")
    void shouldReturnStructuredFailureFromSandbox() {
        ExecuteResponse failed = new ExecuteResponse("sandbox execution failed: ConnectException: x",
                ExecuteResponse.EXIT_CODE_ERROR, false);
        when(sandboxExecutionService.execute(22L, null, "cat nope.txt", 30,
                SandboxCallOrigin.EXECUTOR, 0)).thenReturn(failed);

        Object result = tools.internalRunInSandbox(11L, 22L, "cat nope.txt", 30);

        assertThat(result).isSameAs(failed);
        verify(sandboxExecutionService).execute(22L, null, "cat nope.txt", 30,
                SandboxCallOrigin.EXECUTOR, 0);
    }
    // ────────────────────────────────────────────────────────────
    //  SL-5：台账查询（internal_list_sandbox_calls）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SL-5 internal_list_sandbox_calls：只返回绑定子任务的条目，含 callId / origin / round")
    @SuppressWarnings("unchecked")
    void shouldListSandboxCallsOfBoundSubTask() {
        sandboxCallLedger.record(22L, "pwd", new ExecuteResponse("ok", 0, false),
                SandboxCallOrigin.EXECUTOR, 2);
        sandboxCallLedger.record(22L, "pytest", new ExecuteResponse("fail", 1, false),
                SandboxCallOrigin.EXECUTOR, 3);
        sandboxCallLedger.record(99L, "other", new ExecuteResponse("x", 0, false),
                SandboxCallOrigin.EXECUTOR, 1);

        Map<String, Object> result = (Map<String, Object>) tools.internalListSandboxCalls(999L, 888L, null);

        assertThat(result.get("count")).isEqualTo(2);
        List<Map<String, Object>> calls = (List<Map<String, Object>>) result.get("calls");
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).get("command")).isEqualTo("pwd");
        assertThat(calls.get(0).get("callId")).isNotNull();
        assertThat(calls.get(0).get("origin")).isEqualTo("executor");
        assertThat(calls.get(0).get("round")).isEqualTo(2);
        assertThat(calls.get(1).get("exitCode")).isEqualTo(1);
    }

    @Test
    @DisplayName("SL-5 internal_list_sandbox_calls：limit 缺省 20、钳制到 1~50，取最近 N 条（升序）")
    @SuppressWarnings("unchecked")
    void shouldClampLimitAndTakeTail() {
        for (int i = 0; i < 3; i++) {
            sandboxCallLedger.record(22L, "cmd-" + i, new ExecuteResponse("ok", 0, false),
                    SandboxCallOrigin.EXECUTOR, 1);
        }

        Map<String, Object> one = (Map<String, Object>) tools.internalListSandboxCalls(null, null, 1);
        assertThat(one.get("count")).isEqualTo(1);
        assertThat(((List<Map<String, Object>>) one.get("calls")).get(0).get("command"))
                .isEqualTo("cmd-2");

        Map<String, Object> zero = (Map<String, Object>) tools.internalListSandboxCalls(null, null, 0);
        assertThat(zero.get("count")).isEqualTo(1);

        Map<String, Object> many = (Map<String, Object>) tools.internalListSandboxCalls(null, null, 999);
        assertThat(many.get("count")).isEqualTo(3);
    }

    @Test
    @DisplayName("SL-5 internal_list_sandbox_calls：未绑定执行链上下文 ⇒ 拒绝")
    void shouldRejectListSandboxCallsWithoutChain() {
        ToolInvocationContext.unbind();

        assertThatThrownBy(() -> tools.internalListSandboxCalls(11L, 22L, 10))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅可由平台内部执行链调用");
    }

    // ===== SKX-8：internal_run_skill / internal_read_artifact（2026-10-26） =====

    private static SkillExecutionSupport.SkillTarget archifyTarget() {
        return new SkillExecutionSupport.SkillTarget("archify", "3.1.0", null);
    }

    @Test
    @DisplayName("SKX-8 internal_run_skill：未绑定执行链上下文 ⇒ 拒绝（与其它 internal 工具同闸门）")
    void runSkillShouldRejectWithoutChain() {
        ToolInvocationContext.unbind();

        assertThatThrownBy(() -> tools.internalRunSkill(11L, 22L, "archify",
                "node bin/archify.mjs --help", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("内部执行链");
        verifyNoInteractions(skillExecutionSupport);
    }

    @Test
    @DisplayName("SKX-8 internal_run_skill：技能未入库 ⇒ 拒绝并列出可用包（不静默回落）")
    void runSkillShouldRejectUnknownSkill() {
        when(skillExecutionSupport.resolve("nope")).thenReturn(null);
        when(skillExecutionSupport.availableSkillNames()).thenReturn(List.of("archify"));

        assertThatThrownBy(() -> tools.internalRunSkill(11L, 22L, "nope", "ls", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("技能未入库")
                .hasMessageContaining("archify");
        verify(sandboxExecutionService, never()).installSkillPackage(any(), any(), any(), any());
    }

    @Test
    @DisplayName("SKX-8 internal_run_skill：注入失败 ⇒ 拒绝执行命令（不跑半装包）")
    void runSkillShouldFailWhenInstallFails() {
        when(skillExecutionSupport.resolve("archify")).thenReturn(archifyTarget());
        when(skillExecutionSupport.ensureInstalled(22L, archifyTarget()))
                .thenReturn(SkillInstallResult.fail("技能包加载失败: 清单与实际内容不一致"));

        assertThatThrownBy(() -> tools.internalRunSkill(11L, 22L, "archify", "node bin/archify.mjs --help", 30))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("注入失败")
                .hasMessageContaining("清单与实际内容不一致");
        verify(skillExecutionSupport, never()).run(any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("SKX-8 internal_run_skill：正常路径 ⇒ 返回 skill / skillDir / installed / 执行结果")
    void runSkillShouldReturnStructuredResult() {
        when(skillExecutionSupport.resolve("archify")).thenReturn(archifyTarget());
        when(skillExecutionSupport.ensureInstalled(22L, archifyTarget()))
                .thenReturn(SkillInstallResult.ok(122, 4_330_504L, "/workspace/skills/archify", true, "skipped"));
        when(skillExecutionSupport.run(22L, archifyTarget(), "node bin/archify.mjs doctor", 60, 0))
                .thenReturn(new ExecuteResponse("Archify is ready.", 0, false));

        Object result = tools.internalRunSkill(11L, 22L, "archify", "node bin/archify.mjs doctor", 60);

        assertThat(result).isInstanceOf(Map.class);
        Map<?, ?> map = (Map<?, ?>) result;
        assertThat(map.get("skill")).isEqualTo("archify@3.1.0");
        assertThat(map.get("skillDir")).isEqualTo("/workspace/skills/archify");
        assertThat(map.get("exitCode")).isEqualTo(0);
        assertThat(map.get("output")).isEqualTo("Archify is ready.");
        assertThat(map.get("installed")).isEqualTo(Map.of("files", 122, "bytes", 4_330_504L, "skipped", true));
    }

    @Test
    @DisplayName("SKX-8 internal_read_artifact：产物不存在 ⇒ 拒绝（提示路径与沙箱重建）")
    void readArtifactShouldRejectWhenMissing() {
        when(sandboxExecutionService.readArtifact(22L, "/workspace/out/x.html", 0)).thenReturn(null);

        assertThatThrownBy(() -> tools.internalReadArtifact(11L, 22L, "/workspace/out/x.html", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("产物不存在");
    }

    @Test
    @DisplayName("SKX-8 / SL-3 internal_read_artifact：小产物全文内联；超限内联前 8000 字符并标 truncated")
    void readArtifactShouldInlineOnlySmallContent() {
        when(sandboxExecutionService.readArtifact(22L, "/workspace/out/small.json", 0))
                .thenReturn(new SandboxArtifact("/workspace/out/small.json",
                        "{\"ok\":true}".getBytes(StandardCharsets.UTF_8),
                        "application/json", "a".repeat(64), false));
        when(sandboxExecutionService.readArtifact(22L, "/workspace/out/big.html", 0))
                .thenReturn(new SandboxArtifact("/workspace/out/big.html",
                        new byte[20_000], "text/html", "b".repeat(64), false));

        Map<?, ?> small = (Map<?, ?>) tools.internalReadArtifact(11L, 22L, "/workspace/out/small.json", null);
        Map<?, ?> big = (Map<?, ?>) tools.internalReadArtifact(11L, 22L, "/workspace/out/big.html", null);

        assertThat(small.get("content")).isEqualTo("{\"ok\":true}");
        assertThat(small.get("sha256")).isEqualTo("a".repeat(64));
        assertThat(small.get("truncated")).isEqualTo(false);
        // SL-3：按字符（不是字节）截断后仍内联开头，不再只丢一句「未内联」
        assertThat((String) big.get("content")).hasSize(8_000);
        assertThat(big.get("truncated")).isEqualTo(true);
        assertThat(big.containsKey("note")).isFalse();
        assertThat(big.get("size")).isEqualTo(20_000);
    }

    // ===== SL-3（2026-11-13）：内联长度按字符计（中文不再被字节数误伤） =====

    @Test
    @DisplayName("SL-3 内联：3000 汉字（UTF-8 9000 字节）全文内联；未超 8000 字符不截断")
    void inlineShouldCountCharactersNotBytes() {
        String chinese = "测".repeat(3_000);

        assertThat(chinese.getBytes(StandardCharsets.UTF_8)).hasSize(9_000);
        assertThat(InternalLlmExecutorTools.inlineArtifactText(chinese)).isEqualTo(chinese);
    }

    @Test
    @DisplayName("SL-3 内联：ASCII 边界 8000 字符不截、8001 字符截到 8000")
    void inlineShouldRespectCharacterBoundary() {
        String atLimit = "a".repeat(8_000);
        String overLimit = "a".repeat(8_001);

        assertThat(InternalLlmExecutorTools.inlineArtifactText(atLimit)).isEqualTo(atLimit);
        assertThat(InternalLlmExecutorTools.inlineArtifactText(overLimit)).hasSize(8_000);
        assertThat(InternalLlmExecutorTools.inlineArtifactText(null)).isEmpty();
    }

    @Test
    @DisplayName("SL-3 内联：截断点落在代理对中间时退一格（不产出半个码点）")
    void inlineShouldNotSplitSurrogatePair() {
        String emoji = "😀";           // U+1F600，占两个 char
        String text = "a".repeat(7_999) + emoji + "b";

        String truncated = InternalLlmExecutorTools.inlineArtifactText(text);

        assertThat(truncated).hasSize(7_999);
        assertThat(Character.isHighSurrogate(truncated.charAt(truncated.length() - 1))).isFalse();
    }

    // ===== SKX-11：internal_export_artifact（沙箱字节 → 对象存储 → 附件登记，2026-10-26） =====

    @Test
    @DisplayName("SKX-11 internal_export_artifact：未绑定执行链上下文 ⇒ 拒绝（与其它 internal 工具同闸门）")
    void exportArtifactShouldRejectWithoutChain() {
        ToolInvocationContext.unbind();

        assertThatThrownBy(() -> tools.internalExportArtifact(11L, 22L, "/workspace/out/g.html", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("内部执行链");
        verifyNoInteractions(skillArtifactExportService);
    }

    @Test
    @DisplayName("SKX-11 internal_export_artifact：正常路径 ⇒ 回 attachmentId / fileName / size / sha256")
    void exportArtifactShouldReturnAttachmentIdentity() {
        when(skillArtifactExportService.export(11L, 22L, "/workspace/out/g.html", "arch-graph.html"))
                .thenReturn(new com.helloai.core.agent.service.SkillArtifactExportService.ExportResult(
                        9001L, "arch-graph.html", "text/html", 819_076L, "c".repeat(64)));

        Map<?, ?> result = (Map<?, ?>) tools.internalExportArtifact(
                11L, 22L, "/workspace/out/g.html", "arch-graph.html");

        assertThat(result.get("attachmentId")).isEqualTo(9001L);
        assertThat(result.get("fileName")).isEqualTo("arch-graph.html");
        assertThat(result.get("mime")).isEqualTo("text/html");
        assertThat(result.get("size")).isEqualTo(819_076L);
        assertThat(result.get("sha256")).isEqualTo("c".repeat(64));
        assertThat(result.get("note").toString()).contains("附件");
        // 不外露 storageUrl：模型拿它没用，而内部存储地址属于不该扩散的实现细节
        assertThat(result.containsKey("storageUrl")).isFalse();
    }

    @Test
    @DisplayName("SKX-11 internal_export_artifact：超上限 / 产物不存在 ⇒ 拒绝原因原样透传（不吞成成功）")
    void exportArtifactShouldPropagateRejections() {
        when(skillArtifactExportService.export(11L, 22L, "/workspace/out/huge.html", null))
                .thenThrow(new BizException("产物超过单文件上限（5242880 字节），拒绝导出以免交付被截断的文件"));

        assertThatThrownBy(() -> tools.internalExportArtifact(11L, 22L, "/workspace/out/huge.html", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("单文件上限");
    }
}
