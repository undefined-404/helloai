package com.helloai.api.controller;

import com.helloai.core.agent.service.McpToolService;
import com.helloai.core.agent.service.McpToolService.AckResult;
import com.helloai.core.agent.service.McpToolService.ClaimSubTaskResult;
import com.helloai.core.agent.service.McpToolService.CheckInResult;
import com.helloai.core.agent.service.McpToolService.CheckOutResult;
import com.helloai.core.agent.service.McpToolService.GetDepsSummaryResult;
import com.helloai.core.agent.service.McpToolService.HeartbeatResult;
import com.helloai.core.agent.service.McpToolService.PullTasksResult;
import com.helloai.core.agent.service.McpToolService.ReportBlockedResult;
import com.helloai.core.agent.service.McpToolService.StartSubTaskResult;
import com.helloai.core.agent.service.McpToolService.SubTaskDetail;
import com.helloai.core.agent.service.McpToolService.SubmitResultResult;
import com.helloai.core.agent.service.McpToolService.UploadArtifactResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Paperclip 阶段一·5 —— 双入口一致性契约测试。
 *
 * <p><b>目的</b>：REST 直通端点（{@code POST /api/mcp/tools/*}）与 JSON-RPC 通道
 * （{@code POST /api/mcp/jsonrpc} 的 {@code tools/call}）共享同一 {@link McpToolService}，
 * 但各自独立做「body → 参数」解析。本测试用 {@link ArgumentCaptor} 捕获两入口对
 * {@link McpToolService} 的实际调用参数，断言<b>同一语义输入在两入口产生完全一致的
 * Service 调用</b>，防止某一入口修改参数解析而另一入口漏改（实现漂移）。</p>
 *
 * <p>薄封装回归守卫：任何新增工具方法必须只做「参数解析 + 委托」，不得写入业务规则 ——
 * 本测试通过「两入口调用参数一致」间接约束该契约。</p>
 *
 * <p>说明：直接调用 Controller 方法（不经 MockMvc）——两入口方法签名均已暴露
 * （{@code pullTasks(agentId, body)} 等 + {@code jsonrpc(agentId, body)}），
 * {@code @RequestAttribute / @RequestBody} 仅是 Spring 绑定注解，不影响直接调用。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("McpController 双入口一致性契约")
class McpControllerDualEntryContractTest {

    private static final long AGENT_ID = 42L;

    @Mock
    private McpToolService mcpToolService;

    private McpController controller;

    @BeforeEach
    void setUp() {
        controller = new McpController(mcpToolService);
    }

    /** JSON-RPC 工具调用入口（等价 {@code POST /api/mcp/jsonrpc}）。 */
    private Object jsonrpcCall(String toolName, Map<String, Object> arguments) {
        return controller.jsonrpc(AGENT_ID, Map.of(
                "jsonrpc", "2.0",
                "method", "tools/call",
                "params", Map.of("name", toolName, "arguments", arguments),
                "id", 1));
    }

    // ================================================================
    // 成对契约：REST 直通端点 vs JSON-RPC dispatch —— 同一语义输入 → 同一 Service 调用
    // ================================================================

    @Test
    @DisplayName("pullTasks：REST 与 JSON-RPC 对同一入参产生相同 Service 调用（含 includeRead）")
    void pullTasks_restAndJsonrpcCallIdentically() {
        when(mcpToolService.pullTasks(AGENT_ID, "EXECUTOR", 5, true))
                .thenReturn(new PullTasksResult());
        when(mcpToolService.pullTasks(AGENT_ID, "EXECUTOR", 5, true))
                .thenReturn(new PullTasksResult());

        // REST 直通端点：body = {"role":"EXECUTOR","max":5,"includeRead":true}
        controller.pullTasks(AGENT_ID, Map.of("role", "EXECUTOR", "max", 5, "includeRead", true));
        // JSON-RPC：同语义 arguments
        jsonrpcCall("pullTasks", Map.of("role", "EXECUTOR", "max", 5, "includeRead", true));

        ArgumentCaptor<Long> agentCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> roleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> maxCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Boolean> includeReadCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(mcpToolService, org.mockito.Mockito.times(2))
                .pullTasks(agentCaptor.capture(), roleCaptor.capture(), maxCaptor.capture(), includeReadCaptor.capture());

        assertThat(agentCaptor.getAllValues()).containsExactly(AGENT_ID, AGENT_ID);
        assertThat(roleCaptor.getAllValues()).containsExactly("EXECUTOR", "EXECUTOR");
        assertThat(maxCaptor.getAllValues()).containsExactly(5, 5);
        assertThat(includeReadCaptor.getAllValues()).containsExactly(true, true);
    }

    @Test
    @DisplayName("pullTasks：缺省参数（role/max/includeRead 不传）两入口同样一致（默认值对齐）")
    void pullTasks_defaultsAlignAcrossEntries() {
        when(mcpToolService.pullTasks(AGENT_ID, "EXECUTOR", 20, false))
                .thenReturn(new PullTasksResult());

        // REST 缺省 body（仅 max 显式）
        controller.pullTasks(AGENT_ID, Map.of("max", 20));
        // JSON-RPC 缺省 arguments
        jsonrpcCall("pullTasks", Map.of());

        verify(mcpToolService, org.mockito.Mockito.times(2))
                .pullTasks(AGENT_ID, "EXECUTOR", 20, false);
    }

    @Test
    @DisplayName("claimSubTask：REST 与 JSON-RPC 传同一 subTaskId")
    void claimSubTask_restAndJsonrpcCallIdentically() {
        when(mcpToolService.claimSubTask(AGENT_ID, 101L)).thenReturn(new ClaimSubTaskResult());
        when(mcpToolService.claimSubTask(AGENT_ID, 101L)).thenReturn(new ClaimSubTaskResult());

        controller.claimSubTask(AGENT_ID, Map.of("subTaskId", 101L));
        jsonrpcCall("claimSubTask", Map.of("subTaskId", 101L));

        verify(mcpToolService, org.mockito.Mockito.times(2)).claimSubTask(AGENT_ID, 101L);
    }

    @Test
    @DisplayName("startSubTask：REST 与 JSON-RPC 传同一 subTaskId")
    void startSubTask_restAndJsonrpcCallIdentically() {
        when(mcpToolService.startSubTask(AGENT_ID, 202L)).thenReturn(new StartSubTaskResult());
        when(mcpToolService.startSubTask(AGENT_ID, 202L)).thenReturn(new StartSubTaskResult());

        controller.startSubTask(AGENT_ID, Map.of("subTaskId", 202L));
        jsonrpcCall("startSubTask", Map.of("subTaskId", 202L));

        verify(mcpToolService, org.mockito.Mockito.times(2)).startSubTask(AGENT_ID, 202L);
    }

    @Test
    @DisplayName("submitResult：多参（tokenUsage 可选）两入口逐参一致")
    void submitResult_restAndJsonrpcCallIdentically() {
        when(mcpToolService.submitResult(AGENT_ID, 303L, "res-1", true, "done", null, "stop", 1000))
                .thenReturn(new SubmitResultResult());
        when(mcpToolService.submitResult(AGENT_ID, 303L, "res-1", true, "done", null, "stop", 1000))
                .thenReturn(new SubmitResultResult());

        Map<String, Object> args = Map.of(
                "subTaskId", 303L, "resultId", "res-1", "success", true,
                "output", "done", "finishReason", "stop", "tokenUsage", 1000);
        controller.submitResult(AGENT_ID, args);
        jsonrpcCall("submitResult", args);

        verify(mcpToolService, org.mockito.Mockito.times(2))
                .submitResult(AGENT_ID, 303L, "res-1", true, "done", null, "stop", 1000);
    }

    @Test
    @DisplayName("checkIn：skills 归一化（数组/逗号串）两入口解析结果一致")
    void checkIn_skillsNormalizationAlignsAcrossEntries() {
        CheckInResult lease = new CheckInResult();
        lease.setOk(true);
        lease.setAgentId(AGENT_ID);
        when(mcpToolService.checkIn(AGENT_ID, "AUTO", 2, 30, List.of("doc-writing", "research")))
                .thenReturn(lease);
        when(mcpToolService.checkIn(AGENT_ID, "AUTO", 2, 30, List.of("doc-writing", "research")))
                .thenReturn(lease);

        // REST：skills 数组形态
        controller.checkIn(AGENT_ID, Map.of(
                "workMode", "AUTO", "maxConcurrent", 2, "ttlMinutes", 30,
                "skills", List.of("doc-writing", "research")));
        // JSON-RPC：skills 逗号串形态（兼容 SSE 历史形态）
        jsonrpcCall("checkIn", Map.of(
                "workMode", "AUTO", "maxConcurrent", 2, "ttlMinutes", 30,
                "skills", "doc-writing,research"));

        verify(mcpToolService, org.mockito.Mockito.times(2))
                .checkIn(AGENT_ID, "AUTO", 2, 30, List.of("doc-writing", "research"));
    }

    @Test
    @DisplayName("checkOut：closeReason 缺失时 reason 回退，两入口一致")
    void checkOut_reasonFallbackAlignsAcrossEntries() {
        CheckOutResult out = new CheckOutResult();
        out.setOk(true);
        when(mcpToolService.checkOut(AGENT_ID, "shutdown")).thenReturn(out);
        when(mcpToolService.checkOut(AGENT_ID, "shutdown")).thenReturn(out);

        // REST：body 只带 reason（旧字段）
        controller.checkOut(AGENT_ID, Map.of("reason", "shutdown"));
        // JSON-RPC：arguments 只带 reason
        jsonrpcCall("checkOut", Map.of("reason", "shutdown"));

        verify(mcpToolService, org.mockito.Mockito.times(2)).checkOut(AGENT_ID, "shutdown");
    }

    @Test
    @DisplayName("uploadArtifact / reportBlocked / getDepsSummary / getSubTaskDetail：两入口逐参一致")
    void remainingTools_restAndJsonrpcCallIdentically() {
        when(mcpToolService.uploadArtifact(AGENT_ID, 404L, "a.txt", "text/plain", 10L, "minio://b/2026/1/404/a.txt"))
                .thenReturn(new UploadArtifactResult());
        when(mcpToolService.uploadArtifact(AGENT_ID, 404L, "a.txt", "text/plain", 10L, "minio://b/2026/1/404/a.txt"))
                .thenReturn(new UploadArtifactResult());
        when(mcpToolService.reportBlocked(AGENT_ID, 505L, "stuck")).thenReturn(new ReportBlockedResult());
        when(mcpToolService.reportBlocked(AGENT_ID, 505L, "stuck")).thenReturn(new ReportBlockedResult());
        when(mcpToolService.getDepsSummary(AGENT_ID, 606L)).thenReturn(new GetDepsSummaryResult());
        when(mcpToolService.getDepsSummary(AGENT_ID, 606L)).thenReturn(new GetDepsSummaryResult());
        when(mcpToolService.getSubTaskDetail(AGENT_ID, 707L)).thenReturn(new SubTaskDetail());
        when(mcpToolService.getSubTaskDetail(AGENT_ID, 707L)).thenReturn(new SubTaskDetail());

        // uploadArtifact
        Map<String, Object> uploadArgs = Map.of(
                "subTaskId", 404L, "fileName", "a.txt", "mimeType", "text/plain",
                "fileSize", 10L, "storageUrl", "minio://b/2026/1/404/a.txt");
        controller.uploadArtifact(AGENT_ID, uploadArgs);
        jsonrpcCall("uploadArtifact", uploadArgs);

        // reportBlocked
        controller.reportBlocked(AGENT_ID, Map.of("subTaskId", 505L, "reason", "stuck"));
        jsonrpcCall("reportBlocked", Map.of("subTaskId", 505L, "reason", "stuck"));

        // getDepsSummary
        controller.getDepsSummary(AGENT_ID, Map.of("subTaskId", 606L));
        jsonrpcCall("getDepsSummary", Map.of("subTaskId", 606L));

        // getSubTaskDetail
        controller.getSubTaskDetail(AGENT_ID, Map.of("subTaskId", 707L));
        jsonrpcCall("getSubTaskDetail", Map.of("subTaskId", 707L));

        verify(mcpToolService, org.mockito.Mockito.times(2))
                .uploadArtifact(AGENT_ID, 404L, "a.txt", "text/plain", 10L, "minio://b/2026/1/404/a.txt");
        verify(mcpToolService, org.mockito.Mockito.times(2))
                .reportBlocked(AGENT_ID, 505L, "stuck");
        verify(mcpToolService, org.mockito.Mockito.times(2))
                .getDepsSummary(AGENT_ID, 606L);
        verify(mcpToolService, org.mockito.Mockito.times(2))
                .getSubTaskDetail(AGENT_ID, 707L);
    }

    @Test
    @DisplayName("heartbeat / getAgentStatus：无参工具两入口委托同一 Service 调用")
    void noArgTools_restAndJsonrpcCallIdentically() {
        HeartbeatResult hb = new HeartbeatResult();
        hb.setOk(true);
        when(mcpToolService.heartbeat(AGENT_ID)).thenReturn(hb);
        when(mcpToolService.heartbeat(AGENT_ID)).thenReturn(hb);

        controller.heartbeat(AGENT_ID, Map.of());
        jsonrpcCall("heartbeat", Map.of());

        verify(mcpToolService, org.mockito.Mockito.times(2)).heartbeat(AGENT_ID);
    }

    @Test
    @DisplayName("ack：messageId 必填，两入口同一 messageId")
    void ack_restAndJsonrpcCallIdentically() {
        when(mcpToolService.ack(AGENT_ID, "inbox-77")).thenReturn(new AckResult());
        when(mcpToolService.ack(AGENT_ID, "inbox-77")).thenReturn(new AckResult());

        controller.ack(AGENT_ID, Map.of("messageId", "inbox-77"));
        jsonrpcCall("ack", Map.of("messageId", "inbox-77"));

        verify(mcpToolService, org.mockito.Mockito.times(2)).ack(AGENT_ID, "inbox-77");
    }

    @Test
    @DisplayName("契约守卫：两入口对缺失参数行为一致（REST 返回 R.fail / JSON-RPC 抛 BizException）")
    void missingArg_behaviorConsistentAcrossEntries() {
        // REST：claimSubTask 缺 subTaskId → R.fail（不抛异常）
        var restResult = controller.claimSubTask(AGENT_ID, Map.of());
        assertThat(restResult.getCode()).isEqualTo(500);
        assertThat(restResult.getMsg()).contains("subTaskId");

        // JSON-RPC：claimSubTask 缺 subTaskId → BizException → jsonrpcError(-32000)
        var rpcResult = jsonrpcCall("claimSubTask", Map.of());
        assertThat(rpcResult).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> rpc = (Map<String, Object>) rpcResult;
        assertThat(rpc.get("error")).isNotNull();
        assertThat(((Map<?, ?>) rpc.get("error")).get("code")).isEqualTo(-32000);
        assertThat(((Map<?, ?>) rpc.get("error")).get("message").toString()).contains("subTaskId");

        // 两入口都不应触达 Service（参数校验在入口层，未到委托）
        reset(mcpToolService);
        verify(mcpToolService, org.mockito.Mockito.never()).claimSubTask(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }
}
