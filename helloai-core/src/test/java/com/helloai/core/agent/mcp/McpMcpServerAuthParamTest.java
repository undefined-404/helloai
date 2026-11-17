package com.helloai.core.agent.mcp;

import com.helloai.common.base.BizException;
import com.helloai.core.agent.service.McpToolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link McpMcpServer} 鉴权会话参数优先级单测（ADR-009 A-4）。
 *
 * <p>三名优先级 {@code authSessionId > sessionId > _sessionId}（首个非空白胜出）：
 * 新推荐名 authSessionId 与 MCP 传输会话概念解耦，旧两名保留一个兼容周期。
 * 以 heartbeat（最少业务参）为主载体遍历矩阵，cancelSubTask（A-1 新工具）
 * 抽验同一鉴权装配；全空 → {@code requireAuthId(null)} → 401 fail-closed。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("McpMcpServer 鉴权参数三名优先级（ADR-009 A-4）")
class McpMcpServerAuthParamTest {

    private static final long AUTH_AGENT_ID = 42L;

    @Mock
    private McpToolService mcpToolService;
    @Mock
    private McpAuthContext mcpAuthContext;

    private McpMcpServer server;

    @BeforeEach
    void setUp() {
        server = new McpMcpServer(mcpToolService, mcpAuthContext);
    }

    private void stubAuth(String sid) {
        when(mcpAuthContext.requireAuthId(sid)).thenReturn(AUTH_AGENT_ID);
    }

    @Test
    @DisplayName("单传 authSessionId（新推荐名）：按该名鉴权")
    void authSessionIdAlone() {
        stubAuth("auth-1");

        server.heartbeat(null, "auth-1", null, null);

        verify(mcpAuthContext).requireAuthId("auth-1");
        verify(mcpToolService).heartbeat(AUTH_AGENT_ID);
    }

    @Test
    @DisplayName("单传 sessionId（旧推荐名）：兼容周期内仍有效")
    void sessionIdAlone() {
        stubAuth("old-1");

        server.heartbeat(null, null, "old-1", null);

        verify(mcpAuthContext).requireAuthId("old-1");
        verify(mcpToolService).heartbeat(AUTH_AGENT_ID);
    }

    @Test
    @DisplayName("单传 _sessionId（旧字段名）：兼容周期内仍有效")
    void underscoreSessionIdAlone() {
        stubAuth("legacy-1");

        server.heartbeat(null, null, null, "legacy-1");

        verify(mcpAuthContext).requireAuthId("legacy-1");
        verify(mcpToolService).heartbeat(AUTH_AGENT_ID);
    }

    @Test
    @DisplayName("authSessionId + sessionId 同传：authSessionId 胜出")
    void authSessionIdBeatsSessionId() {
        stubAuth("auth-1");

        server.heartbeat(null, "auth-1", "old-1", null);

        verify(mcpAuthContext).requireAuthId("auth-1");
        verify(mcpToolService).heartbeat(AUTH_AGENT_ID);
    }

    @Test
    @DisplayName("sessionId + _sessionId 同传（新名缺席）：sessionId 胜出")
    void sessionIdBeatsUnderscore() {
        stubAuth("old-1");

        server.heartbeat(null, null, "old-1", "legacy-1");

        verify(mcpAuthContext).requireAuthId("old-1");
        verify(mcpToolService).heartbeat(AUTH_AGENT_ID);
    }

    @Test
    @DisplayName("三名同传：authSessionId 胜出（优先级钉死）")
    void allThree_authSessionIdWins() {
        stubAuth("auth-1");

        server.heartbeat(null, "auth-1", "old-1", "legacy-1");

        verify(mcpAuthContext).requireAuthId("auth-1");
        verify(mcpToolService).heartbeat(AUTH_AGENT_ID);
    }

    @Test
    @DisplayName("authSessionId 空白（非 null）：视同缺席，回退 sessionId")
    void blankAuthSessionId_fallsBack() {
        stubAuth("old-1");

        server.heartbeat(null, "   ", "old-1", "legacy-1");

        verify(mcpAuthContext).requireAuthId("old-1");
        verify(mcpToolService).heartbeat(AUTH_AGENT_ID);
    }

    @Test
    @DisplayName("三名全空/空白：以 null 鉴权 → 401 fail-closed，不触达业务")
    void allBlank_401() {
        when(mcpAuthContext.requireAuthId((String) null))
                .thenThrow(new BizException(401, "MCP 鉴权失败：缺少 authSessionId 参数"));

        assertThatThrownBy(() -> server.heartbeat(null, null, "  ", null))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", 401);

        verify(mcpToolService, never()).heartbeat(anyLong());
    }

    @Test
    @DisplayName("agentId 覆盖：客户端伪造 agentId 被鉴权主体覆盖（三名改造不削弱防越权）")
    void forgedAgentId_overridden() {
        stubAuth("auth-1");

        server.heartbeat(999L, "auth-1", null, null);

        verify(mcpToolService).heartbeat(AUTH_AGENT_ID);
        verify(mcpToolService, never()).heartbeat(999L);
    }

    @Test
    @DisplayName("cancelSubTask（A-1 新工具）：同一三名优先级装配 + 鉴权主体委托")
    void cancelSubTask_sameAuthAssembly() {
        stubAuth("auth-1");

        server.cancelSubTask(999L, 5L, "auth-1", "old-1", "legacy-1");

        verify(mcpAuthContext).requireAuthId("auth-1");
        verify(mcpToolService).cancelSubTask(AUTH_AGENT_ID, 5L);
    }
}