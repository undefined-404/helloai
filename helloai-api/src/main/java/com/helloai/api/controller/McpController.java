package com.helloai.api.controller;

import com.helloai.common.base.BizException;
import com.helloai.common.base.R;
import com.helloai.core.service.McpToolService;
import com.helloai.core.service.McpToolService.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * MCP 工具端点 —— REST 风格 + JSON-RPC 双通道。
 * <p>
 * REST 通道用于 curl 测试和管理台调用；JSON-RPC 通道用于 MCP 兼容客户端（Qoder/Trae/CLI）。
 * 认证：走现有 AuthInterceptor（Authorization: Bearer &lt;API_KEY&gt;），agentId 从 _authId 注入。
 *
 * <p><b style="color:orange">[M4 起标记为 @Deprecated]</b><br>
 * v2.4 §3.1/§9 路线 C 阶段 3 MCP Server spring-ai 改造后，外部 MCP 客户端应改走
 * <b>SSE 通道</b>：
 * <ul>
 *   <li>建立连接：{@code GET /mcp/sse} → 拿 sessionId</li>
 *   <li>握手：{@code POST /mcp/messages?sessionId=xxx} body=JSON-RPC 2.0 {@code initialize}</li>
 *   <li>调用工具：{@code POST /mcp/messages?sessionId=xxx} body=JSON-RPC 2.0 {@code tools/call}（带 {@code Authorization: Bearer <apiKey>}）</li>
 *   <li>响应：通过 SSE 长连接异步推回（data: {"jsonrpc":"2.0","id":..,"result":..}）</li>
 * </ul>
 * 鉴权从 {@link com.helloai.core.mcp.McpAuthFilter McpAuthFilter} 接入（POST /mcp/messages）。<br>
 * 业务实现：{@link com.helloai.core.mcp.McpMcpServer}（spring-ai {@code @Tool} 注解方法）。<br>
 * 本类仅作为兼容旧客户端保留，<b>不再演进</b>，预计下个大版本移除。
 *
 * @deprecated since 2.4 — 改用 spring-ai MCP Server SSE 通道（{@code /mcp/sse} + {@code /mcp/messages}）
 */
@Deprecated(since = "2.4", forRemoval = false)
@Slf4j
@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
public class McpController {

    private final McpToolService mcpToolService;

    // ================================================================
    // REST 通道 — 6 个工具
    // ================================================================

    /** GET /api/mcp/tools — 列出当前 Agent 可用的工具 */
    @GetMapping("/tools")
    public R<?> listTools(@RequestAttribute("_authId") Long agentId) {
        return R.ok(java.util.List.of("pullTasks", "ack", "claimSubTask", "heartbeat", "uploadArtifact", "reportBlocked"));
    }

    /** POST /api/mcp/tools/pullTasks */
    @PostMapping("/tools/pullTasks")
    public R<PullTasksResult> pullTasks(
            @RequestAttribute("_authId") Long agentId,
            @RequestBody Map<String, Object> body) {
        String role = (String) body.getOrDefault("role", "EXECUTOR");
        int max = body.get("max") instanceof Number n ? n.intValue() : 20;
        return R.ok(mcpToolService.pullTasks(agentId, role, max));
    }

    /** POST /api/mcp/tools/ack */
    @PostMapping("/tools/ack")
    public R<AckResult> ack(
            @RequestAttribute("_authId") Long agentId,
            @RequestBody Map<String, Object> body) {
        String messageId = (String) body.get("messageId");
        if (messageId == null || messageId.isBlank()) {
            return R.fail("messageId 不能为空");
        }
        return R.ok(mcpToolService.ack(agentId, messageId));
    }

    /** POST /api/mcp/tools/claimSubTask */
    @PostMapping("/tools/claimSubTask")
    public R<ClaimSubTaskResult> claimSubTask(
            @RequestAttribute("_authId") Long agentId,
            @RequestBody Map<String, Object> body) {
        Long subTaskId = toLong(body.get("subTaskId"));
        if (subTaskId == null) {
            return R.fail("subTaskId 不能为空");
        }
        return R.ok(mcpToolService.claimSubTask(agentId, subTaskId));
    }

    /** POST /api/mcp/tools/heartbeat */
    @PostMapping("/tools/heartbeat")
    public R<HeartbeatResult> heartbeat(
            @RequestAttribute("_authId") Long agentId,
            @RequestBody Map<String, Object> body) {
        return R.ok(mcpToolService.heartbeat(agentId));
    }

    /** POST /api/mcp/tools/uploadArtifact */
    @PostMapping("/tools/uploadArtifact")
    public R<UploadArtifactResult> uploadArtifact(
            @RequestAttribute("_authId") Long agentId,
            @RequestBody Map<String, Object> body) {
        Long subTaskId = toLong(body.get("subTaskId"));
        String fileName = (String) body.get("fileName");
        String mimeType = (String) body.get("mimeType");
        Long fileSize = body.get("fileSize") instanceof Number n ? n.longValue() : null;
        String storageUrl = (String) body.get("storageUrl");

        if (subTaskId == null) return R.fail("subTaskId 不能为空");
        if (fileName == null || fileName.isBlank()) return R.fail("fileName 不能为空");
        if (storageUrl == null || storageUrl.isBlank()) return R.fail("storageUrl 不能为空");

        return R.ok(mcpToolService.uploadArtifact(agentId, subTaskId, fileName, mimeType, fileSize, storageUrl));
    }

    /** POST /api/mcp/tools/reportBlocked */
    @PostMapping("/tools/reportBlocked")
    public R<ReportBlockedResult> reportBlocked(
            @RequestAttribute("_authId") Long agentId,
            @RequestBody Map<String, Object> body) {
        Long subTaskId = toLong(body.get("subTaskId"));
        String reason = (String) body.get("reason");

        if (subTaskId == null) return R.fail("subTaskId 不能为空");
        if (reason == null || reason.isBlank()) return R.fail("reason 不能为空");

        return R.ok(mcpToolService.reportBlocked(agentId, subTaskId, reason));
    }

    // ================================================================
    // JSON-RPC 2.0 通道（MCP 兼容）
    // ================================================================

    /**
     * POST /api/mcp/jsonrpc
     * <p>
     * 请求格式: {"jsonrpc":"2.0","method":"tools/call","params":{"name":"pullTasks","arguments":{...}},"id":1}
     * 响应格式: {"jsonrpc":"2.0","result":{...},"id":1}
     */
    @PostMapping("/jsonrpc")
    public Map<String, Object> jsonrpc(
            @RequestAttribute("_authId") Long agentId,
            @RequestBody Map<String, Object> body) {

        String method = (String) body.get("method");
        Object id = body.get("id");

        // 支持 tools/list 和 tools/call
        if ("tools/list".equals(method)) {
            return jsonrpcOk(id, Map.of("tools", java.util.List.of(
                    Map.of("name", "pullTasks", "description", "拉取待处理收件箱消息"),
                    Map.of("name", "ack", "description", "确认消息已处理"),
                    Map.of("name", "claimSubTask", "description", "原子认领子任务"),
                    Map.of("name", "heartbeat", "description", "心跳上报"),
                    Map.of("name", "uploadArtifact", "description", "注册产物附件元数据"),
                    Map.of("name", "reportBlocked", "description", "上报任务阻塞")
            )));
        }

        if (!"tools/call".equals(method)) {
            return jsonrpcError(id, -32601, "Method not found: " + method);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) body.get("params");
        if (params == null) {
            return jsonrpcError(id, -32602, "Missing params");
        }

        String toolName = (String) params.get("name");
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) params.get("arguments");
        if (args == null) args = Map.of();

        try {
            Object result = dispatch(agentId, toolName, args);
            return jsonrpcOk(id, result);
        } catch (BizException e) {
            log.warn("MCP 工具调用失败: agentId={}, tool={}, error={}", agentId, toolName, e.getMessage());
            return jsonrpcError(id, -32000, e.getMessage());
        } catch (Exception e) {
            log.error("MCP 工具调用异常: agentId={}, tool={}", agentId, toolName, e);
            return jsonrpcError(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private Object dispatch(Long agentId, String toolName, Map<String, Object> args) {
        return switch (toolName) {
            case "pullTasks" -> {
                String role = (String) args.getOrDefault("role", "EXECUTOR");
                int max = args.get("max") instanceof Number n ? n.intValue() : 20;
                yield mcpToolService.pullTasks(agentId, role, max);
            }
            case "ack" -> {
                String messageId = (String) args.get("messageId");
                if (messageId == null || messageId.isBlank())
                    throw new BizException("messageId is required");
                yield mcpToolService.ack(agentId, messageId);
            }
            case "claimSubTask" -> {
                Long subTaskId = toLong(args.get("subTaskId"));
                if (subTaskId == null)
                    throw new BizException("subTaskId is required");
                yield mcpToolService.claimSubTask(agentId, subTaskId);
            }
            case "heartbeat" -> mcpToolService.heartbeat(agentId);
            case "uploadArtifact" -> {
                Long subTaskId = toLong(args.get("subTaskId"));
                String fileName = (String) args.get("fileName");
                String mimeType = (String) args.get("mimeType");
                Long fileSize = args.get("fileSize") instanceof Number n ? n.longValue() : null;
                String storageUrl = (String) args.get("storageUrl");
                if (subTaskId == null) throw new BizException("subTaskId is required");
                if (fileName == null || fileName.isBlank()) throw new BizException("fileName is required");
                if (storageUrl == null || storageUrl.isBlank()) throw new BizException("storageUrl is required");
                yield mcpToolService.uploadArtifact(agentId, subTaskId, fileName, mimeType, fileSize, storageUrl);
            }
            case "reportBlocked" -> {
                Long subTaskId = toLong(args.get("subTaskId"));
                String reason = (String) args.get("reason");
                if (subTaskId == null) throw new BizException("subTaskId is required");
                if (reason == null || reason.isBlank()) throw new BizException("reason is required");
                yield mcpToolService.reportBlocked(agentId, subTaskId, reason);
            }
            default -> throw new BizException("Unknown tool: " + toolName);
        };
    }

    // ================================================================
    // helpers
    // ================================================================

    private Long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try { return Long.valueOf(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private Map<String, Object> jsonrpcOk(Object id, Object result) {
        return Map.of("jsonrpc", "2.0", "result", result, "id", id != null ? id : 0);
    }

    private Map<String, Object> jsonrpcError(Object id, int code, String message) {
        return Map.of(
                "jsonrpc", "2.0",
                "error", Map.of("code", code, "message", message),
                "id", id != null ? id : 0);
    }
}
