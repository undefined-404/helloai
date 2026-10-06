package com.helloai.core.agent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.entity.AgentMcpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * {@code AgentMcpServerServiceImpl} 接入类型过滤单测（2026-10-06 L3 P1-1）。
 *
 * <p>缺陷：内部 LLM 执行者（{@code API_KEY_LLM}、{@code supportsMCP=false}）被等价注入 13 个
 * MCP 生命周期工具，进程内调用 100% 命中 {@code McpAuthContext.requireAuthId} 空 sessionId 分支
 * → 401，模型重试至 {@code MAX_ITERATIONS}（L3 实测占内循环约 14.8%）。</p>
 *
 * <p>本类只验证「授权结果 → 可注入结果」的过滤口径，不触碰 DB
 * （{@code spy} + 拦截 {@code lambdaQuery()}，与 {@code AttachmentServiceImplTest} 同范式）。</p>
 */
@DisplayName("AgentMcpServerServiceImpl 接入类型工具过滤（L3 P1-1）")
class AgentMcpServerServiceImplTest {

    /** 13 个 MCP 会话类工具（与本单 MCP_SESSION_TOOLS 同集合，逐个列出以冻结清单）。 */
    private static final List<String> MCP_TOOLS = List.of(
            "pullTasks", "ack", "claimSubTask", "startSubTask", "getSubTaskDetail", "heartbeat",
            "uploadArtifact", "submitResult", "reportBlocked", "getAgentStatus", "getDepsSummary",
            "checkIn", "checkOut");

    private AgentMcpServerServiceImpl service;
    private LambdaQueryChainWrapper<AgentMcpServer> chain;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        // @RequiredArgsConstructor 无字段 ⇒ 无参构造；spy 后拦截 lambdaQuery()，避免触碰 MyBatis-Plus
        service = spy(new AgentMcpServerServiceImpl());
        chain = mock(LambdaQueryChainWrapper.class);
        doReturn(chain).when(service).lambdaQuery();
        when(chain.eq(any(SFunction.class), any())).thenReturn(chain);
    }

    private List<AgentMcpServer> rowsOf(List<String> toolNames) {
        return toolNames.stream().map(name -> {
            AgentMcpServer row = new AgentMcpServer();
            row.setToolName(name);
            row.setIsEnabled(1);
            return row;
        }).toList();
    }

    @Test
    @DisplayName("API_KEY_LLM：13 个 MCP 会话工具全部剔除，非 MCP 工具保留")
    void shouldExcludeMcpSessionToolsForApiKeyLlm() {
        // 授权集 = 13 个 MCP 工具 + 1 个非 MCP 工具（后续若引入内部工具，应被保留）
        List<String> authorized = List.of(
                "pullTasks", "ack", "claimSubTask", "startSubTask", "getSubTaskDetail", "heartbeat",
                "uploadArtifact", "submitResult", "reportBlocked", "getAgentStatus", "getDepsSummary",
                "checkIn", "checkOut", "some_internal_tool");
        when(chain.list()).thenReturn(rowsOf(authorized));

        List<String> injectable = service.getEnabledToolsForAccess(11L, AgentAccessType.API_KEY_LLM);

        // 13 个 MCP 工具一个都不能留
        assertThat(injectable).doesNotContainAnyElementsOf(MCP_TOOLS);
        // 非 MCP 工具必须保留（过滤只针对 MCP 会话工具，不得误删）
        assertThat(injectable).containsExactly("some_internal_tool");
    }

    @Test
    @DisplayName("CLI_CLIENT / WEB_BROWSER / null：与 getEnabledTools 逐字一致（外部链路零回归）")
    void shouldReturnIdenticalToGetEnabledToolsForNonApiKeyLlm() {
        List<String> authorized = List.of(
                "pullTasks", "ack", "claimSubTask", "startSubTask", "getSubTaskDetail", "heartbeat",
                "uploadArtifact", "submitResult", "reportBlocked", "getAgentStatus", "getDepsSummary",
                "checkIn", "checkOut");
        when(chain.list()).thenReturn(rowsOf(authorized));

        List<String> baseline = service.getEnabledTools(11L);

        // ★外部 Agent（trae-excutor / TeleAgent-executor）为 CLI_CLIENT，13 工具注入必须逐字不变
        assertThat(service.getEnabledToolsForAccess(11L, AgentAccessType.CLI_CLIENT))
                .containsExactlyElementsOf(baseline);
        assertThat(service.getEnabledToolsForAccess(11L, AgentAccessType.WEB_BROWSER))
                .containsExactlyElementsOf(baseline);
        assertThat(service.getEnabledToolsForAccess(11L, null))
                .containsExactlyElementsOf(baseline);
    }

    @Test
    @DisplayName("getEnabledTools 原语义不变：仍返回含 13 个 MCP 工具的授权全集")
    void shouldNotChangeGetEnabledToolsSemantics() {
        List<String> authorized = List.of(
                "pullTasks", "ack", "claimSubTask", "startSubTask", "getSubTaskDetail", "heartbeat",
                "uploadArtifact", "submitResult", "reportBlocked", "getAgentStatus", "getDepsSummary",
                "checkIn", "checkOut");
        when(chain.list()).thenReturn(rowsOf(authorized));

        // 授权查询（其它调用点）仍取全集：MCP 工具不得被本单改动隐式剔除
        assertThat(service.getEnabledTools(11L)).containsExactlyElementsOf(MCP_TOOLS);
        // 而「可注入」视图对 API_KEY_LLM 为空（全部是 MCP 会话工具）
        assertThat(service.getEnabledToolsForAccess(11L, AgentAccessType.API_KEY_LLM)).isEmpty();
    }
}