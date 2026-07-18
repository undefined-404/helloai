package com.helloai.core.agent.mcp;

import lombok.Data;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP Server 连通性验证工具。
 * <p>
 * 用于客户端（如 MCP Inspector / Qoder / Trae）首次连接 helloai MCP Server 时验证：
 * <ol>
 *   <li>SSE 端点可达（{@code /mcp/sse}）</li>
 *   <li>协议握手正常（{@code initialize} → {@code tools/list} → {@code tools/call}）</li>
 *   <li>JSON Schema 序列化正常（@ToolParam 的 description / required 反映到 schema）</li>
 * </ol>
 * <p>
 * 业务零副作用：纯诊断，不读不写 helloai 任何状态。
 *
 * <p><b>Spring AI 版本注意事项</b>：spring-ai 1.0 GA 用 {@link org.springframework.ai.tool.annotation.Tool}
 * 通用注解；{@code @McpTool} 是 1.1.x+ 才引入的别名，包路径迁到 {@code org.springframework.ai.mcp.annotation}。
 * 当前项目锁 1.0.0，所以这里用 {@code @Tool}。
 */
@Component
public class EchoMcpTool {

    @Tool(description = """
            【何时使用】客户端首次连接 helloai MCP Server、调试 MCP 协议握手、或排查 SSE 长连接时调用。
            【调用频率】连接建立后调用一次即可；调试期间可重复调用，无副作用。
            【Gotchas】
            - 任何非空字符串原样回显（repeat 次拼接）
            - 不会修改 helloai 任何业务状态、不会写日志到 task_timeline、不会调 HeartbeatService
            - 返回 EchoResult 包含 ok/message/repeated/echoed 四个字段
            【相关工具】无（纯诊断工具）
            """)
    public EchoResult echo(
            @ToolParam(description = "要回显的字符串，建议 1-200 字符", required = true) String message,
            @ToolParam(description = "回显重复次数，范围 1-10，默认 1", required = false) Integer repeat) {
        int n;
        if (repeat == null || repeat < 1) {
            n = 1;
        } else {
            n = Math.min(repeat, 10);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(message);
        }
        EchoResult r = new EchoResult();
        r.setOk(true);
        r.setMessage(message);
        r.setRepeated(n);
        r.setEchoed(sb.toString());
        r.setServerTime(java.time.OffsetDateTime.now().toString());
        return r;
    }

    @Data
    public static class EchoResult {
        private boolean ok;
        private String message;
        private int repeated;
        private String echoed;
        private String serverTime;
    }
}
