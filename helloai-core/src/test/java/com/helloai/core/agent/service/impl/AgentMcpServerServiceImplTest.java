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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code AgentMcpServerServiceImpl} 接入类型过滤单测（2026-10-06 L3 P1-1）。
 *
 * <p>缺陷：内部 LLM 执行者（{@code API_KEY_LLM}、{@code supportsMCP=false}）被等价注入 13 个
 * MCP 生命周期工具，进程内调用 100% 命中 {@code McpAuthContext.requireAuthId} 空 sessionId 分支
 * → 401，模型重试至 {@code MAX_ITERATIONS}（L3 实测占内循环约 14.8%）。</p>
 *
 * <p><b>REF-1.3b 增补</b>：不可关闭清单（{@code CRITICAL_TOOLS} = pullTasks / submitResult / heartbeat）
 * 的授权面生效口径——关键工具短路判启用、写进禁用列表仍在列表内；并**钉死边界**：
 * 该清单<b>不</b>抵销 {@code API_KEY_LLM} 的 MCP 会话过滤（否则原样复活上述缺陷）。</p>
 *
 * <p>本类只验证「授权结果 → 可注入结果」的过滤口径 + 不可关闭下界，不触碰 DB
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
        // lenient：不可关闭清单用例（REF-1.3b）走短路路径，根本不发起查询、也用不到本桩
        lenient().doReturn(chain).when(service).lambdaQuery();
        lenient().when(chain.eq(any(SFunction.class), any())).thenReturn(chain);
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

    // #region REF-1.3b 不可关闭清单（CRITICAL_TOOLS）

    /** 不可关闭三要素（与实现同名常量同集合，逐个列出以冻结清单）。 */
    private static final List<String> CRITICAL_TOOLS = List.of("pullTasks", "submitResult", "heartbeat");

    @Test
    @DisplayName("不可关闭：关键工具一律判启用，且短路在查询之前（从不查库、也从不写库）")
    void shouldShortCircuitCriticalToolsBeforeQuery() {
        for (String tool : CRITICAL_TOOLS) {
            assertThat(service.isToolEnabled(11L, tool))
                    .as("critical tool 必须不可关闭: %s", tool).isTrue();
        }
        // 「短路在查询之前」：DB 里就算存着 is_enabled=0 的禁用行也读不到——因为压根不查
        verify(service, never()).lambdaQuery();
        // 且消除关键工具的「自动补行」写副作用（读路径保持纯读）
        verify(service, never()).save(any());
    }

    @Test
    @DisplayName("短路口径不得扩大：非关键工具仍走原查询逻辑（禁用行 ⇒ false）")
    void shouldKeepOriginalQueryLogicForNonCriticalTool() {
        AgentMcpServer disabled = new AgentMcpServer();
        disabled.setToolName("some_internal_tool");
        disabled.setIsEnabled(0);
        when(chain.one()).thenReturn(disabled);

        assertThat(service.isToolEnabled(11L, "some_internal_tool")).isFalse();
    }

    @Test
    @DisplayName("getEnabledTools 并集：仅授权 1 个非关键工具时仍返回 该工具 ∪ 3 个不可关闭工具")
    void shouldUnionCriticalToolsIntoEnabledTools() {
        when(chain.list()).thenReturn(rowsOf(List.of("some_internal_tool")));

        assertThat(service.getEnabledTools(11L)).containsExactlyInAnyOrder(
                "some_internal_tool", "pullTasks", "submitResult", "heartbeat");
    }

    @Test
    @DisplayName("★ REF-1.3b 边界：不可关闭清单不抵销 API_KEY_LLM 的 MCP 会话过滤（防 L3 P1-1 复活）")
    void shouldNotReAddCriticalToolsForApiKeyLlm() {
        when(chain.list()).thenReturn(rowsOf(List.of("some_internal_tool")));

        // 授权面（getEnabledTools）已含 3 个关键工具（并集兜底）
        assertThat(service.getEnabledTools(11L)).contains("pullTasks", "submitResult", "heartbeat");
        // 但可注入面（进程内 LLM 执行者，无 MCP 会话）必须依旧剔除——
        // 否则必然 401 并重试至 MAX_ITERATIONS（2026-10-06 L3 P1-1，占内循环约 14.8%）
        assertThat(service.getEnabledToolsForAccess(11L, AgentAccessType.API_KEY_LLM))
                .containsExactly("some_internal_tool");
    }

    // #endregion
}