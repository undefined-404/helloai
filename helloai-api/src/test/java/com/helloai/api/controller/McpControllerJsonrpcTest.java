package com.helloai.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.core.agent.mcp.McpToolService;
import com.helloai.core.agent.mcp.McpToolService.CheckInResult;
import com.helloai.core.agent.mcp.McpToolService.CheckOutResult;
import com.helloai.core.agent.mcp.McpToolService.GetAgentStatusResult;
import com.helloai.core.agent.mcp.McpToolService.GetDepsSummaryResult;
import com.helloai.core.agent.mcp.McpToolService.HeartbeatResult;
import com.helloai.core.agent.mcp.McpToolService.PullTasksResult;
import com.helloai.core.agent.mcp.McpToolService.SubmitResultResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 别名通道 POST /api/mcp/jsonrpc 单测（A0-2 §6.61）：
 *
 * <ul>
 *   <li>同步响应：tools/call 返回 JSON-RPC result（含 accepted/resultId/status），不再像
 *       SSE 通道 POST 那样静默 200 空 body</li>
 *   <li>tools/list：11 工具声明齐全且每个工具都带 JSON Schema（inputSchema）</li>
 *   <li>无状态复用：不依赖 MCP session（无 sessionId 参数），会话过期/断连后仍可调用</li>
 *   <li>错误语义：未知 method -32601、未知工具/参数缺失 -32000（BizException）</li>
 * </ul>
 *
 * <p>说明：通道鉴权由 AuthInterceptor（/api/**）负责，此处 standalone 只测 Controller 逻辑。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("McpController REST 别名通道（A0-2）")
class McpControllerJsonrpcTest {

    private static final long AGENT_ID = 9L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private McpToolService mcpToolService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new McpController(mcpToolService))
                .build();
    }

    private MvcResult postJsonrpc(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/mcp/jsonrpc")
                        .requestAttr("_authId", AGENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
    }

    @Test
    @DisplayName("tools/list：11 工具齐全且每个工具带 inputSchema（无状态 Schema 声明）")
    void toolsList_hasAllTenToolsWithSchema() throws Exception {
        MvcResult result = postJsonrpc(Map.of("jsonrpc", "2.0", "method", "tools/list", "id", 1));
        JsonNode root = MAPPER.readTree(result.getResponse().getContentAsString());

        assertEquals("2.0", root.get("jsonrpc").asText());
        assertEquals(1, root.get("id").asInt());

        JsonNode tools = root.get("result").get("tools");
        assertNotNull(tools, "tools 数组不应为空");
        assertEquals(11, tools.size(), "应声明 11 个工具（与 MCP SSE 通道对齐，A0-4 新增 getDepsSummary）");

        List<String> expectedNames = List.of(
                "pullTasks", "ack", "claimSubTask", "heartbeat", "uploadArtifact",
                "submitResult", "reportBlocked", "getAgentStatus", "getDepsSummary",
                "checkIn", "checkOut");
        for (String name : expectedNames) {
            JsonNode tool = null;
            for (JsonNode t : tools) {
                if (name.equals(t.get("name").asText())) {
                    tool = t;
                    break;
                }
            }
            assertNotNull(tool, "缺少工具声明: " + name);
            assertTrue(tool.has("description"), name + " 应有 description");
            JsonNode schema = tool.get("inputSchema");
            assertNotNull(schema, name + " 应带 inputSchema（JSON Schema）");
            assertEquals("object", schema.get("type").asText(), name + " inputSchema.type 应为 object");
        }
    }

    @Test
    @DisplayName("tools/call submitResult：同步返回 accepted/resultId/status")
    void toolsCallSubmitResult_returnsSyncReceipt() throws Exception {
        SubmitResultResult receipt = new SubmitResultResult();
        receipt.setOk(true);
        receipt.setAccepted(true);
        receipt.setIdempotent(false);
        receipt.setStatus("REVIEW");
        receipt.setSubTaskId(101L);
        receipt.setResultId("res-20260811-001");
        when(mcpToolService.submitResult(anyLong(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(receipt);

        MvcResult result = postJsonrpc(Map.of(
                "jsonrpc", "2.0",
                "method", "tools/call",
                "params", Map.of(
                        "name", "submitResult",
                        "arguments", Map.of(
                                "subTaskId", 101L,
                                "resultId", "res-20260811-001",
                                "success", true,
                                "output", "done")),
                "id", 2));
        JsonNode root = MAPPER.readTree(result.getResponse().getContentAsString());

        assertTrue(root.has("result"), "应有同步 result（非 SSE 静默空 body）");
        JsonNode res = root.get("result");
        assertEquals(true, res.get("accepted").asBoolean(), "同步回执应含 accepted");
        assertEquals("res-20260811-001", res.get("resultId").asText(), "同步回执应含 resultId");
        assertEquals("REVIEW", res.get("status").asText(), "同步回执应含 status");
        assertEquals(2, root.get("id").asInt());
    }

    @Test
    @DisplayName("tools/call checkIn：同步返回打卡租约（leaseId/expiresAt）")
    void toolsCallCheckIn_returnsLeaseSync() throws Exception {
        CheckInResult lease = new CheckInResult();
        lease.setOk(true);
        lease.setAgentId(AGENT_ID);
        lease.setLeaseId(55L);
        lease.setSessionId("lease-session-55");
        lease.setWorkMode("AUTO");
        lease.setMaxConcurrent(2);
        lease.setExpiresAt("2026-08-11T18:00:00");
        when(mcpToolService.checkIn(anyLong(), anyString(), any(), any())).thenReturn(lease);

        MvcResult result = postJsonrpc(Map.of(
                "jsonrpc", "2.0",
                "method", "tools/call",
                "params", Map.of("name", "checkIn", "arguments", Map.of("workMode", "AUTO")),
                "id", 3));
        JsonNode root = MAPPER.readTree(result.getResponse().getContentAsString());

        JsonNode res = root.get("result");
        assertEquals(55L, res.get("leaseId").asLong(), "打卡应同步返回 leaseId");
        assertTrue(res.has("expiresAt"), "应同步返回租约过期时间");
        assertEquals("AUTO", res.get("workMode").asText());
    }

    @Test
    @DisplayName("tools/call heartbeat：同步返回结果")
    void toolsCallHeartbeat_returnsSync() throws Exception {
        HeartbeatResult hb = new HeartbeatResult();
        hb.setOk(true);
        hb.setAgentId(AGENT_ID);
        hb.setServerTime("2026-08-11T17:00:00");
        when(mcpToolService.heartbeat(AGENT_ID)).thenReturn(hb);

        MvcResult result = postJsonrpc(Map.of(
                "jsonrpc", "2.0",
                "method", "tools/call",
                "params", Map.of("name", "heartbeat", "arguments", Map.of()),
                "id", 4));
        JsonNode root = MAPPER.readTree(result.getResponse().getContentAsString());

        assertEquals(true, root.get("result").get("ok").asBoolean());
    }

    @Test
    @DisplayName("未知 method：-32601 Method not found")
    void unknownMethod_returns32601() throws Exception {
        MvcResult result = postJsonrpc(Map.of(
                "jsonrpc", "2.0", "method", "tools/unknown", "id", 5));
        JsonNode root = MAPPER.readTree(result.getResponse().getContentAsString());

        assertEquals(-32601, root.get("error").get("code").asInt());
        assertTrue(root.get("error").get("message").asText().contains("tools/unknown"));
    }

    @Test
    @DisplayName("未知工具：BizException 转 -32000")
    void unknownTool_returns32000() throws Exception {
        MvcResult result = postJsonrpc(Map.of(
                "jsonrpc", "2.0",
                "method", "tools/call",
                "params", Map.of("name", "noSuchTool", "arguments", Map.of()),
                "id", 6));
        JsonNode root = MAPPER.readTree(result.getResponse().getContentAsString());

        assertEquals(-32000, root.get("error").get("code").asInt());
        assertTrue(root.get("error").get("message").asText().contains("Unknown tool"));
    }

    @Test
    @DisplayName("参数缺失（claimSubTask 无 subTaskId）：-32000 参数错误")
    void missingRequiredArg_returns32000() throws Exception {
        MvcResult result = postJsonrpc(Map.of(
                "jsonrpc", "2.0",
                "method", "tools/call",
                "params", Map.of("name", "claimSubTask", "arguments", Map.of()),
                "id", 7));
        JsonNode root = MAPPER.readTree(result.getResponse().getContentAsString());

        assertEquals(-32000, root.get("error").get("code").asInt());
        assertTrue(root.get("error").get("message").asText().contains("subTaskId is required"));
    }

    @Test
    @DisplayName("业务失败（BizException）：-32000 且携带业务错误信息")
    void bizException_returns32000WithMessage() throws Exception {
        when(mcpToolService.pullTasks(AGENT_ID, "EXECUTOR", 20, false))
                .thenThrow(new BizException("agent 不在 ACTIVE 状态"));

        MvcResult result = postJsonrpc(Map.of(
                "jsonrpc", "2.0",
                "method", "tools/call",
                "params", Map.of("name", "pullTasks", "arguments", Map.of()),
                "id", 8));
        JsonNode root = MAPPER.readTree(result.getResponse().getContentAsString());

        assertEquals(-32000, root.get("error").get("code").asInt());
        assertTrue(root.get("error").get("message").asText().contains("ACTIVE"));
    }

    // ================================================================
    // REST 直通端点 /api/mcp/tools/*（A0-3：三通道 10 工具对齐；A0-4：11 工具 + getDepsSummary）
    // ================================================================

    @Test
    @DisplayName("GET /api/mcp/tools：声明 11 个工具且与 JSON-RPC tools/list 同名集合一致")
    void listTools_declaresAllTenMatchingJsonrpc() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/mcp/tools")
                        .requestAttr("_authId", AGENT_ID))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = MAPPER.readTree(result.getResponse().getContentAsString());
        assertEquals(200, root.get("code").asInt(), "R.ok 应返回 code=200");
        assertNotNull(root.get("data"), "R.ok 应携带 data");

        List<String> declared = new java.util.ArrayList<>();
        for (JsonNode t : root.get("data")) {
            declared.add(t.asText());
        }
        List<String> expected = List.of(
                "pullTasks", "ack", "claimSubTask", "heartbeat", "uploadArtifact",
                "submitResult", "reportBlocked", "getAgentStatus", "getDepsSummary",
                "checkIn", "checkOut");
        assertEquals(expected, declared, "GET /api/mcp/tools 声明应与三通道统一清单一致");
    }

    @Test
    @DisplayName("POST /api/mcp/tools/getAgentStatus：直通端点委托 McpToolService（无 body 可用）")
    void directGetAgentStatus_delegates() throws Exception {
        GetAgentStatusResult status = new GetAgentStatusResult();
        status.setAgentId(AGENT_ID);
        status.setStatus("ACTIVE");
        status.setComputedOnlineStatus("ONLINE");
        when(mcpToolService.getAgentStatus(AGENT_ID)).thenReturn(status);

        MvcResult result = mockMvc.perform(post("/api/mcp/tools/getAgentStatus")
                        .requestAttr("_authId", AGENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = MAPPER.readTree(result.getResponse().getContentAsString());
        assertEquals("ACTIVE", root.get("data").get("status").asText());
        assertEquals("ONLINE", root.get("data").get("computedOnlineStatus").asText());
    }

    @Test
    @DisplayName("POST /api/mcp/tools/pullTasks：includeRead=true 透传 4 参（A0-4）")
    void directPullTasks_passesIncludeRead() throws Exception {
        PullTasksResult pull = new PullTasksResult();
        pull.setMessages(List.of());
        when(mcpToolService.pullTasks(AGENT_ID, "EXECUTOR", 20, true)).thenReturn(pull);

        mockMvc.perform(post("/api/mcp/tools/pullTasks")
                        .requestAttr("_authId", AGENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"EXECUTOR\",\"max\":20,\"includeRead\":true}"))
                .andExpect(status().isOk());

        verify(mcpToolService).pullTasks(AGENT_ID, "EXECUTOR", 20, true);
    }

    @Test
    @DisplayName("POST /api/mcp/tools/pullTasks：includeRead 缺省为 false（3 参语义保持）")
    void directPullTasks_defaultsIncludeReadFalse() throws Exception {
        PullTasksResult pull = new PullTasksResult();
        pull.setMessages(List.of());
        when(mcpToolService.pullTasks(AGENT_ID, "EXECUTOR", 20, false)).thenReturn(pull);

        mockMvc.perform(post("/api/mcp/tools/pullTasks")
                        .requestAttr("_authId", AGENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"EXECUTOR\",\"max\":20}"))
                .andExpect(status().isOk());

        verify(mcpToolService).pullTasks(AGENT_ID, "EXECUTOR", 20, false);
    }

    @Test
    @DisplayName("POST /api/mcp/tools/getDepsSummary：直通端点委托 McpToolService（A0-4）")
    void directGetDepsSummary_delegates() throws Exception {
        GetDepsSummaryResult summary = new GetDepsSummaryResult();
        summary.setSubTaskId(123L);
        summary.setDepCount(0);
        summary.setDeps(List.of());
        when(mcpToolService.getDepsSummary(AGENT_ID, 123L)).thenReturn(summary);

        MvcResult result = mockMvc.perform(post("/api/mcp/tools/getDepsSummary")
                        .requestAttr("_authId", AGENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subTaskId\":123}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = MAPPER.readTree(result.getResponse().getContentAsString());
        assertEquals(123, root.get("data").get("subTaskId").asLong());
        assertEquals(0, root.get("data").get("depCount").asInt());
    }

    @Test
    @DisplayName("POST /api/mcp/tools/getDepsSummary：缺 subTaskId 返回 R.fail 不抛异常")
    void directGetDepsSummary_requiresSubTaskId() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/mcp/tools/getDepsSummary")
                        .requestAttr("_authId", AGENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = MAPPER.readTree(result.getResponse().getContentAsString());
        assertEquals(500, root.get("code").asInt(), "R.fail 应返回 code=500");
        assertTrue(root.get("msg").asText().contains("subTaskId"));
    }

    @Test
    @DisplayName("POST /api/mcp/tools/checkIn：直通端点透传 workMode/ttlMinutes 并同步返回租约")
    void directCheckIn_passesBodyAndReturnsLease() throws Exception {
        CheckInResult lease = new CheckInResult();
        lease.setOk(true);
        lease.setAgentId(AGENT_ID);
        lease.setLeaseId(88L);
        lease.setWorkMode("AUTO");
        lease.setMaxConcurrent(2);
        lease.setExpiresAt("2026-08-11T20:00:00");
        when(mcpToolService.checkIn(AGENT_ID, "AUTO", null, 45)).thenReturn(lease);

        MvcResult result = mockMvc.perform(post("/api/mcp/tools/checkIn")
                        .requestAttr("_authId", AGENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("workMode", "AUTO", "ttlMinutes", 45))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = MAPPER.readTree(result.getResponse().getContentAsString());
        assertEquals(88L, root.get("data").get("leaseId").asLong());
        assertEquals("AUTO", root.get("data").get("workMode").asText());
    }

    @Test
    @DisplayName("POST /api/mcp/tools/checkOut：直通端点 closeReason 缺失时回退 reason（兼容旧字段）")
    void directCheckOut_fallsBackToReason() throws Exception {
        CheckOutResult out = new CheckOutResult();
        out.setOk(true);
        out.setAgentId(AGENT_ID);
        out.setClosedCount(1);
        out.setReason("shutdown");
        when(mcpToolService.checkOut(AGENT_ID, "shutdown")).thenReturn(out);

        MvcResult result = mockMvc.perform(post("/api/mcp/tools/checkOut")
                        .requestAttr("_authId", AGENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("reason", "shutdown"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = MAPPER.readTree(result.getResponse().getContentAsString());
        assertEquals(1, root.get("data").get("closedCount").asInt());
        assertEquals("shutdown", root.get("data").get("reason").asText());
    }
}
