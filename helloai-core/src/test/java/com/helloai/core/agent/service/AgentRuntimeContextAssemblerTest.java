package com.helloai.core.agent.service;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.AgentLlmCredentialResolver;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.quality.service.AgentQualityProfileService;
import com.helloai.core.agent.runtime.AgentContext;
import com.helloai.core.agent.runtime.AgentExecutionResult;
import com.helloai.core.agent.runtime.LocalProcessEnvironment;
import com.helloai.core.agent.runtime.loop.LoopCheckpoint;
import com.helloai.core.agent.runtime.loop.LoopCheckpointListener;
import com.helloai.core.agent.session.service.AgentSessionService;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.agent.tool.ToolRegistry;
import com.helloai.core.agent.port.AttachmentPort;
import com.helloai.core.agent.port.AttachmentRef;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.port.TaskRunningSpecPort;
import com.helloai.core.agent.port.TaskTimelinePort;
import com.helloai.core.agent.port.UncertaintySnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentRuntimeContextAssembler 单测（G-002 单轨装配器定向补测，2026-09-30）。
 *
 * <p>背景：装配器承接旧链 {@code SubTaskExecutionServiceImpl.executeOnce} 的装配职责（688 行），
 * 承载全链唯一 fail-close 点（真实模式 API Key 缺失即失败）；单轨硬切时未随迁专属测试
 * （Consumer 测试中为 {@code @Mock}，其内部逻辑零断言——审计 §11.4 风险 2）。本测试按
 * 审计 §11.7 建议补定向覆盖：① prompt 各段装配分支（spec/技能规范/历史画像/依赖/恢复/返工，
 * 空段零注入）② fail-close 四态 ③ best-effort 降级（画像/恢复上下文/对话流/依赖段均不阻断）
 * ④ 会话推进（含 checkpoint 回调装配与恢复段 loop 进度渲染）/ timeline / 工具并集边界。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentRuntimeContextAssembler（单轨装配器）")
class AgentRuntimeContextAssemblerTest {

    @Mock
    private AgentChatClientService agentChatClientService;

    @Mock
    private AgentLlmCredentialResolver agentLlmCredentialResolver;

    @Mock
    private TaskTimelinePort taskTimelinePort;

    @Mock
    private TaskRunningSpecPort taskRunningSpecPort;

    @Mock
    private AgentSkillSpecService agentSkillSpecService;

    @Mock
    private AgentQualityProfileService agentQualityProfileService;

    @Mock
    private AgentSessionService agentSessionService;

    @Mock
    private ConversationService conversationService;

    @Mock
    private AttachmentPort attachmentPort;

    @Mock
    private SubTaskQueryPort subTaskQueryPort;

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private AgentEventRecorder agentEventRecorder;

    @Mock
    private ChatModel mockChatModel;

    private AgentExecutionProperties properties;

    private AgentRuntimeContextAssembler assembler;

    @BeforeEach
    void setUp() {
        properties = new AgentExecutionProperties();
        properties.setMockMode(true);
        properties.setProvider("mock");
        assembler = new AgentRuntimeContextAssembler(properties, agentChatClientService, agentLlmCredentialResolver,
                taskTimelinePort, taskRunningSpecPort, agentSkillSpecService, agentQualityProfileService,
                agentSessionService, conversationService, attachmentPort, subTaskQueryPort, toolRegistry,
                agentEventRecorder);
    }

    // #region 状态守卫

    @Nested
    @DisplayName("状态守卫：不可执行终态直接拒绝（fail-close，不产生半装配副作用）")
    class StatusGuard {

        @Test
        @DisplayName("DONE → BizException，且不触达任何装配依赖")
        void shouldRejectWhenDone() {
            SubTaskFixture subTask = subTask();
            subTask.setStatus(SubTaskStatus.DONE);

            assertThatThrownBy(() -> invokeAssemble(subTask, agent(), List.of(), List.of()))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("子任务不可执行");

            verify(taskRunningSpecPort, never()).buildExecutorPromptSection(any());
            verify(agentSessionService, never()).start(any(), any(), any(), anyInt(), anyInt(), any());
        }

        @Test
        @DisplayName("CANCELLED → BizException")
        void shouldRejectWhenCancelled() {
            SubTaskFixture subTask = subTask();
            subTask.setStatus(SubTaskStatus.CANCELLED);

            assertThatThrownBy(() -> invokeAssemble(subTask, agent(), List.of(), List.of()))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("子任务不可执行");
        }
    }

    // #endregion

    // #region Prompt 装配分支

    @Nested
    @DisplayName("Prompt 装配：各段注入与空段零注入")
    class PromptAssembly {

        @Test
        @DisplayName("全段注入：spec/技能规范/历史画像/四要素/约束/不确定性/恢复/返工/回填块齐全")
        void shouldAssembleAllSectionsWhenPresent() {
            SubTaskFixture subTask = subTask();
            subTask.setContent("完成 A 与 B");
            subTask.setDeliverable("report.md");
            subTask.setAcceptance("全部通过");
            subTask.setConstraints("不要动 X");
            subTask.setUncertainties(List.of(
                    new UncertaintySnapshot("ASSUMPTION", "假设可用", true),
                    new UncertaintySnapshot("UNCONFIRMED", "接口待确认", false)));
            subTask.setContext(Map.of("reviewHistory", List.of(Map.of(
                    "ts", "2026-09-30T04:00:00Z",
                    "issues", List.of("问题 A", "问题 B"),
                    "comment", "请修正",
                    "score", 3))));
            Agent agent = agent();

            when(taskRunningSpecPort.buildExecutorPromptSection(33L)).thenReturn("【运行规格段】");
            when(agentSkillSpecService.resolve(anyList())).thenReturn(new AgentSkillSpecService.ResolvedSpec(
                    List.of("eng-code-review"), List.of("eng-code-review"), "【技能规范段】",
                    Map.of(), List.of("web_search")));
            when(agentQualityProfileService.renderHistorySection(11L)).thenReturn("【历史画像段】");
            when(agentSessionService.findLatestInterrupted(22L)).thenReturn(new AgentSessionService.InterruptedSession(
                    100L, 11L, 1, 2, "ABORTED", "租约回收", Map.of("depCount", 0)));
            when(agentChatClientService.buildChatModel(any(), anyString(), any())).thenReturn(mockChatModel);

            AgentContext context = invokeAssemble(subTask, agent, List.of("pullTasks"), List.of("eng-code-review"));
            String prompt = context.getUserPrompt();

            assertThat(prompt)
                    .contains("【运行规格段】", "【技能规范段】", "【历史画像段】")
                    .contains("## 当前任务")
                    .contains("任务标题: 测试子任务")
                    .contains("任务描述: 完成 A 与 B")
                    .contains("交付物要求: report.md")
                    .contains("验收标准: 全部通过")
                    .contains("执行约束（不许改的事）: 不要动 X")
                    .contains("[ASSUMPTION] 假设可用（可自行验证，推翻即上报）")
                    .contains("[UNCONFIRMED] 接口待确认（须先验证再动手，无法验证则 BLOCKED 上报）")
                    .contains("验收事实回源")
                    .contains("## 执行恢复上下文（接续执行）")
                    .contains("会话 ID：100")
                    .contains("step=2（上下文装配已完成，LLM 调用尚未开始）")
                    .contains("ABORTED（租约过期被回收中断）")
                    .contains("中断原因摘要：租约回收")
                    .contains("上次执行上下文装配事实：")
                    .contains("## 返工修正指引（共 1 轮历史审核）")
                    .contains("问题 A")
                    .contains("审核评分: 3 / 5")
                    .contains("## 产出回填要求")
                    .contains("## EXECUTION_RECORD");

            assertThat(context.getRunId()).isEqualTo("run-33-1");
            assertThat(context.getTaskId()).isEqualTo(33L);
            assertThat(context.getSubTaskId()).isEqualTo(22L);
            assertThat(context.getAgentId()).isEqualTo(11L);
            assertThat(context.getTurn()).isEqualTo(1);
            assertThat(context.getStep()).isZero();
            assertThat(context.getSkills()).containsExactly("eng-code-review");
            assertThat(context.getTools()).containsExactly("pullTasks", "web_search");
            assertThat(context.getChatModel()).isSameAs(mockChatModel);
        }

        @Test
        @DisplayName("空段零注入：无 spec/技能/画像/依赖/恢复/返工时仅保留四要素与固定块")
        void shouldSkipOptionalSectionsWhenAbsent() {
            SubTaskFixture subTask = subTask();

            AgentContext context = invokeAssemble(subTask, agent(), List.of(), List.of());
            String prompt = context.getUserPrompt();

            assertThat(prompt)
                    .contains("## 当前任务")
                    .contains("任务标题: 测试子任务")
                    .contains("验收事实回源")
                    .contains("## 产出回填要求")
                    .doesNotContain("任务描述: ")
                    .doesNotContain("交付物要求: ")
                    .doesNotContain("验收标准: ")
                    .doesNotContain("执行约束（不许改的事）")
                    .doesNotContain("不确定性申报")
                    .doesNotContain("## 依赖产出参考")
                    .doesNotContain("## 执行恢复上下文")
                    .doesNotContain("## 返工修正指引");
            assertThat(context.getTools()).isEmpty();
        }

        @Test
        @DisplayName("不确定性分级：blank note 跳过，ASSUMPTION/UNCONFIRMED 后缀各自渲染")
        void shouldRenderUncertaintySuffixesAndSkipBlankNote() {
            SubTaskFixture subTask = subTask();
            subTask.setUncertainties(List.of(
                    new UncertaintySnapshot("ASSUMPTION", "可用性假设", true),
                    new UncertaintySnapshot("UNCONFIRMED", "对外接口", false),
                    new UncertaintySnapshot("ASSUMPTION", "   ", true)));

            String prompt = invokeAssemble(subTask, agent(), List.of(), List.of()).getUserPrompt();

            assertThat(prompt)
                    .contains("[ASSUMPTION] 可用性假设（可自行验证，推翻即上报）")
                    .contains("[UNCONFIRMED] 对外接口（须先验证再动手，无法验证则 BLOCKED 上报）")
                    .containsOnlyOnce("- [ASSUMPTION]");
        }

        @Test
        @DisplayName("返工上下文：reviewHistory 多轮铺开（时间/问题/评语/评分/已修复）")
        void shouldRenderReworkContextFromReviewHistory() {
            SubTaskFixture subTask = subTask();
            subTask.setContext(Map.of("reviewHistory", List.of(
                    Map.of("ts", "2026-09-29T01:00:00Z", "issues", List.of("一轮问题"),
                            "comment", "一轮评语", "score", 2, "executorDoneIssues", List.of("一轮自认修复")),
                    Map.of("issues", "二轮问题"))));

            String prompt = invokeAssemble(subTask, agent(), List.of(), List.of()).getUserPrompt();

            assertThat(prompt)
                    .contains("## 返工修正指引（共 2 轮历史审核）")
                    .contains("### 第 1 轮")
                    .contains("### 第 2 轮")
                    .contains("一轮问题")
                    .contains("审核评语: 一轮评语")
                    .contains("审核评分: 2 / 5")
                    .contains("上一轮你已自认修复: 一轮自认修复")
                    .contains("审核问题: 二轮问题")
                    .contains("请务必针对未自认修复的问题继续修正后重新提交。");
        }

        @Test
        @DisplayName("返工上下文兼容：无 reviewHistory 时回退 lastAutoReview 单轮")
        void shouldFallbackToLegacyLastAutoReview() {
            SubTaskFixture subTask = subTask();
            subTask.setContext(Map.of("lastAutoReview", Map.of("comment", "旧格式评语", "issues", List.of("I1"))));

            String prompt = invokeAssemble(subTask, agent(), List.of(), List.of()).getUserPrompt();

            assertThat(prompt)
                    .contains("## 返工修正指引（共 1 轮历史审核）")
                    .contains("旧格式评语");
        }

        @Test
        @DisplayName("依赖段：前置内容装载 + 超 4000 字符截断 + 统计进 timeline")
        void shouldBuildDependencySectionWithTruncation() {
            SubTaskFixture subTask = subTask();
            subTask.setDependsOn(List.of(7L));
            SubTaskFixture dep = new SubTaskFixture();
            dep.setId(7L);
            dep.setTitle("上游子任务");
            dep.setStatus(SubTaskStatus.DONE);
            dep.setContext(Map.of("lastExecution", Map.of("output", "x".repeat(5000))));
            when(subTaskQueryPort.listByIds(anyList())).thenReturn(List.of(dep.toSnapshot()));

            String prompt = invokeAssemble(subTask, agent(), List.of(), List.of()).getUserPrompt();

            assertThat(prompt)
                    .contains("## 依赖产出参考（直接前置）")
                    .contains("### 前置 1：上游子任务（状态：DONE）")
                    .contains("[TRUNCATED] shown=4000 total=5000 reason=dep_content_limit")
                    .contains("必须在交付物中显式声明缺失项");
            Map<String, Object> payload = captureTimelinePayload("sub_task_spec_context_loaded");
            assertThat(payload)
                    .containsEntry("depCount", 1)
                    .containsEntry("loadedCount", 1)
                    .containsEntry("truncatedCount", 1)
                    .containsEntry("degraded", false);
        }

        @Test
        @DisplayName("依赖段：多附件正序拼接并带来源标题行（P-1：不再只取倒序第一个附件）")
        void shouldConcatenateAllAttachmentsInCreationOrder() {
            SubTaskFixture subTask = subTask();
            subTask.setDependsOn(List.of(7L));
            SubTaskFixture dep = new SubTaskFixture();
            dep.setId(7L);
            dep.setTitle("上游子任务");
            dep.setStatus(SubTaskStatus.DONE);
            when(subTaskQueryPort.listByIds(anyList())).thenReturn(List.of(dep.toSnapshot()));

            // listActive 按 createTime 倒序返回：附录（晚创建）在前——修复前下游只拿到附录
            AttachmentRef appendix = new AttachmentRef(2L, "appendix.md", null, null, null, null, true);
            AttachmentRef main = new AttachmentRef(1L, "main.md", null, null, null, null, true);
            when(attachmentPort.listActive(7L)).thenReturn(List.of(appendix, main));
            when(attachmentPort.loadContent(2L)).thenReturn("附录正文".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            when(attachmentPort.loadContent(1L)).thenReturn("主文件正文".getBytes(java.nio.charset.StandardCharsets.UTF_8));

            String prompt = invokeAssemble(subTask, agent(), List.of(), List.of()).getUserPrompt();

            assertThat(prompt)
                    .contains("【文件：main.md】\n主文件正文")
                    .contains("【文件：appendix.md】\n附录正文");
            assertThat(prompt.indexOf("main.md")).isLessThan(prompt.indexOf("appendix.md"));
        }

        @Test
        @DisplayName("依赖段：主附件超预算时次附件仍可见（R2 配额渲染，不再被单点截断整体吃掉）")
        void shouldKeepMinorAttachmentVisibleWhenMainOversized() {
            SubTaskFixture subTask = subTask();
            subTask.setDependsOn(List.of(7L));
            SubTaskFixture dep = new SubTaskFixture();
            dep.setId(7L);
            dep.setTitle("上游子任务");
            dep.setStatus(SubTaskStatus.DONE);
            when(subTaskQueryPort.listByIds(anyList())).thenReturn(List.of(dep.toSnapshot()));

            AttachmentRef appendix = new AttachmentRef(2L, "appendix.md", null, null, null, null, true);
            AttachmentRef main = new AttachmentRef(1L, "main.md", null, null, null, null, true);
            when(attachmentPort.listActive(7L)).thenReturn(List.of(appendix, main));
            // 近似 tku-e2e-01 真实规模：主文件 7809 + 附录 19294，拼接 27,103 字符——
            // 修复前单点截断 4000 时附录整体不可见（审计 §15.4）
            when(attachmentPort.loadContent(2L))
                    .thenReturn("A".repeat(19294).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            when(attachmentPort.loadContent(1L))
                    .thenReturn("M".repeat(7809).getBytes(java.nio.charset.StandardCharsets.UTF_8));

            String prompt = invokeAssemble(subTask, agent(), List.of(), List.of()).getUserPrompt();

            // 次附件最低配额 500 仍可见 + 逐附件 [TRUNCATED] file= 标注可机读
            assertThat(prompt)
                    .contains("【文件：main.md】")
                    .contains("【文件：appendix.md】")
                    .contains("A".repeat(500))
                    .contains("[TRUNCATED] file=appendix.md shown=500 total=19294 reason=dep_content_limit");
            // R2 统计口径：附件路径逐附件截断并入 truncatedCount（顶层未触发）
            Map<String, Object> payload = captureTimelinePayload("sub_task_spec_context_loaded");
            assertThat(payload)
                    .containsEntry("depCount", 1)
                    .containsEntry("loadedCount", 1)
                    .containsEntry("truncatedCount", 1);
        }

        @Test
        @DisplayName("依赖段：超限截断回退到行边界（回退窗口内最近换行，不拦腰切断）")
        void shouldTruncateAtLineBoundary() {
            SubTaskFixture subTask = subTask();
            subTask.setDependsOn(List.of(7L));
            SubTaskFixture dep = new SubTaskFixture();
            dep.setId(7L);
            dep.setTitle("上游子任务");
            dep.setStatus(SubTaskStatus.DONE);
            // 换行在 3500（回退窗口 floor=3488 与上限 4000 之间）→ 截点回退到 3500
            dep.setContext(Map.of("lastExecution", Map.of("output", "x".repeat(3500) + "\n" + "y".repeat(2000))));
            when(subTaskQueryPort.listByIds(anyList())).thenReturn(List.of(dep.toSnapshot()));

            String prompt = invokeAssemble(subTask, agent(), List.of(), List.of()).getUserPrompt();

            assertThat(prompt)
                    .contains("[TRUNCATED] shown=3500 total=5501 reason=dep_content_limit")
                    .doesNotContain("y".repeat(100));
        }

        @Test
        @DisplayName("依赖段降级：查询异常不阻断装配，degraded=true 零注入")
        void shouldDegradeWhenDependencyQueryFails() {
            SubTaskFixture subTask = subTask();
            subTask.setDependsOn(List.of(7L));
            when(subTaskQueryPort.listByIds(anyList())).thenThrow(new RuntimeException("db down"));

            String prompt = invokeAssemble(subTask, agent(), List.of(), List.of()).getUserPrompt();

            assertThat(prompt).doesNotContain("## 依赖产出参考");
            Map<String, Object> payload = captureTimelinePayload("sub_task_spec_context_loaded");
            assertThat(payload)
                    .containsEntry("depCount", 1)
                    .containsEntry("degraded", true);
        }
    }

    // #endregion

    // #region 恢复上下文

    @Nested
    @DisplayName("恢复上下文：中断摘要注入与查询异常降级")
    class RecoveryContext {

        @Test
        @DisplayName("最小注入：step=4/FAILED/无快照/无 error 时仍渲染中断行")
        void shouldInjectMinimalRecoveryWhenSnapshotEmpty() {
            SubTaskFixture subTask = subTask();
            when(agentSessionService.findLatestInterrupted(22L)).thenReturn(new AgentSessionService.InterruptedSession(
                    100L, 11L, 1, 4, "FAILED", null, Map.of()));

            String prompt = invokeAssemble(subTask, agent(), List.of(), List.of()).getUserPrompt();

            assertThat(prompt)
                    .contains("## 执行恢复上下文（接续执行）")
                    .contains("step=4（LLM 调用已完成，结果回写前中断）")
                    .contains("FAILED（上次执行失败）")
                    .doesNotContain("上次执行上下文装配事实")
                    .doesNotContain("中断原因摘要");
        }

        @Test
        @DisplayName("查询异常降级：findLatestInterrupted 抛异常 → 零注入零阻断")
        void shouldDegradeWhenRecoveryQueryThrows() {
            SubTaskFixture subTask = subTask();
            when(agentSessionService.findLatestInterrupted(anyLong())).thenThrow(new RuntimeException("db down"));

            String prompt = invokeAssemble(subTask, agent(), List.of(), List.of()).getUserPrompt();

            assertThat(prompt).doesNotContain("## 执行恢复上下文");
            Map<String, Object> payload = captureTimelinePayload("sub_task_spec_context_loaded");
            assertThat(payload).containsEntry("recoveryInjected", false);
        }

        @Test
        @DisplayName("循环进度注入：snapshot.loop 渲染已完成轮数/工具执行/已执行工具")
        void shouldInjectLoopProgressFromSnapshot() {
            SubTaskFixture subTask = subTask();
            when(agentSessionService.findLatestInterrupted(22L)).thenReturn(new AgentSessionService.InterruptedSession(
                    100L, 11L, 2, 4, "FAILED", "llm timeout", Map.of(
                            "loop", Map.of("iteration", 2, "toolCallCount", 3, "messageCount", 6,
                                    "executedTools", List.of("pullTasks", "submitResult")))));

            String prompt = invokeAssemble(subTask, agent(), List.of(), List.of()).getUserPrompt();

            assertThat(prompt)
                    .contains("上次循环进度：已完成 2 轮 LLM 调用、3 次工具执行")
                    .contains("已执行工具：pullTasks、submitResult");
        }
    }

    // #endregion

    // #region 凭据 fail-close

    @Nested
    @DisplayName("凭据 fail-close（全链唯一）：真实模式 API Key 缺失即失败")
    class CredentialFailClose {

        @Test
        @DisplayName("mock 模式：跳过凭据解析，模型直接构建成功")
        void shouldSkipCredentialResolutionInMockMode() {
            when(agentChatClientService.buildChatModel(any(), anyString(), any())).thenReturn(mockChatModel);

            AgentContext context = invokeAssemble(subTask(), agent(), List.of(), List.of());

            assertThat(context.getChatModel()).isSameAs(mockChatModel);
            verify(agentLlmCredentialResolver, never()).resolveApiKey(any());
        }

        @Test
        @DisplayName("真实模式 + requireVault + 无 Key → BizException，且不启动会话/不记录 timeline")
        void shouldFailCloseWhenRealModeApiKeyMissing() {
            properties.setMockMode(false);
            properties.setRequireVault(true);

            assertThatThrownBy(() -> invokeAssemble(subTask(), agent(), List.of(), List.of()))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("未配置可用的平台级或 Agent 级 API Key");

            verify(agentChatClientService, never()).buildChatModel(any(), anyString(), any());
            verify(agentSessionService, never()).start(any(), any(), any(), anyInt(), anyInt(), any());
            verify(taskTimelinePort, never()).recordEvent(any(), any(), anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("真实模式 + requireVault + 有 Key → 按 provider 构建真实模型")
        void shouldBuildRealModelWhenApiKeyPresent() {
            properties.setMockMode(false);
            properties.setRequireVault(true);
            Agent agent = agent();
            agent.setModelType("deepseek:deepseek-chat");
            when(agentLlmCredentialResolver.resolveApiKey(agent)).thenReturn("sk-test");
            when(agentChatClientService.buildChatModel(agent, "deepseek", "sk-test")).thenReturn(mockChatModel);

            AgentContext context = invokeAssemble(subTask(), agent, List.of(), List.of());

            assertThat(context.getChatModel()).isSameAs(mockChatModel);
            verify(agentChatClientService).buildChatModel(agent, "deepseek", "sk-test");
        }

        @Test
        @DisplayName("真实模式 + 不要求 Vault + 无 Key → 兼容全局 Provider 配置，放行")
        void shouldAllowRealModeWhenVaultNotRequired() {
            properties.setMockMode(false);
            when(agentChatClientService.buildChatModel(any(), anyString(), isNull())).thenReturn(mockChatModel);

            AgentContext context = invokeAssemble(subTask(), agent(), List.of(), List.of());

            assertThat(context.getChatModel()).isSameAs(mockChatModel);
        }
    }

    // #endregion

    // #region best-effort 降级

    @Nested
    @DisplayName("best-effort 降级：副链路异常不阻断执行主链路")
    class BestEffortDegradation {

        @Test
        @DisplayName("历史画像异常 → 空串零注入，装配继续")
        void shouldDegradeWhenHistoryProfileThrows() {
            when(agentQualityProfileService.renderHistorySection(anyLong())).thenThrow(new RuntimeException("db"));

            AgentContext context = invokeAssemble(subTask(), agent(), List.of(), List.of());

            assertThat(context.getUserPrompt()).doesNotContain("【历史");
            Map<String, Object> payload = captureTimelinePayload("sub_task_spec_context_loaded");
            assertThat(payload).containsEntry("historySummary", false);
        }

        @Test
        @DisplayName("对话流落库异常 → 不阻断，上下文照常返回")
        void shouldNotBlockWhenConversationPersistFails() {
            when(conversationService.addMessage(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("db"));

            AgentContext context = invokeAssemble(subTask(), agent(), List.of(), List.of());

            assertThat(context).isNotNull();
            assertThat(context.getUserPrompt()).isNotBlank();
        }
    }

    // #endregion

    // #region 会话 / 事件 / 工具并集

    @Nested
    @DisplayName("会话推进 / timeline / 工具并集")
    class SessionAndEvents {

        @Test
        @DisplayName("装配完成：session.start(step=2) 携装配快照，timeline 两事件按序落库")
        void shouldStartSessionWithSnapshotAndRecordTimeline() {
            SubTaskFixture subTask = subTask();
            subTask.setReworkCount(2);

            invokeAssemble(subTask, agent(), List.of("pullTasks"), List.of("eng-code-review"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> snapshotCaptor = ArgumentCaptor.forClass(Map.class);
            verify(agentSessionService).start(eq(33L), eq(22L), eq(11L), eq(3), eq(2), snapshotCaptor.capture());
            assertThat(snapshotCaptor.getValue())
                    .containsEntry("agentId", 11L)
                    .containsEntry("tools", List.of("pullTasks"))
                    .containsEntry("environment", "local-process")
                    .containsEntry("depCount", 0);

            verify(taskTimelinePort).recordEvent(any(), any(), eq("sub_task_spec_context_loaded"),
                    eq(AgentRole.EXECUTOR), eq(11L), any());
            verify(taskTimelinePort).recordEvent(any(), any(), eq("sub_task_llm_call_start"),
                    eq(AgentRole.EXECUTOR), eq(11L), any());
            verify(conversationService).addMessage(eq(22L), eq(11L), eq("user"), eq("agent"), anyString(),
                    eq("sub_task_execute_user_prompt"));
        }

        @Test
        @DisplayName("afterTurn：advance(step=4) + llm_call_end 携带 success/finishReason/tokens")
        void shouldAdvanceSessionAndRecordLlmEnd() {
            assembler.afterTurn(subTask().toSnapshot(), agent(), 1, AgentExecutionResult.builder()
                    .status(ExecutionStatus.SUCCESS).finishReason("STOP").tokenUsage(123).build());

            verify(agentSessionService).advance(22L, 11L, 1, 4);
            Map<String, Object> payload = captureTimelinePayload("sub_task_llm_call_end");
            assertThat(payload)
                    .containsEntry("success", true)
                    .containsEntry("finishReason", "STOP")
                    .containsEntry("tokens", 123);
        }

        @Test
        @DisplayName("afterTurn 降级：advance 异常被吞掉，timeline 仍记录")
        void shouldSwallowAdvanceFailureButStillRecordTimeline() {
            doThrow(new RuntimeException("db")).when(agentSessionService).advance(anyLong(), anyLong(), anyInt(), anyInt());

            assembler.afterTurn(subTask().toSnapshot(), agent(), 1, AgentExecutionResult.builder()
                    .status(ExecutionStatus.FAILED).build());

            verify(taskTimelinePort).recordEvent(any(), any(), eq("sub_task_llm_call_end"),
                    any(), any(), any());
        }

        @Test
        @DisplayName("工具并集：命令 tools ∪ 技能 requiredTools（去重保序），空技能零变化")
        void shouldMergeCommandToolsWithSkillRequiredTools() {
            when(agentSkillSpecService.resolve(anyList())).thenReturn(new AgentSkillSpecService.ResolvedSpec(
                    List.of("eng-code-review"), List.of("eng-code-review"), "【技能规范段】",
                    Map.of(), List.of("web_search", "pullTasks")));

            AgentContext context = invokeAssemble(subTask(), agent(),
                    List.of("pullTasks", "submitResult"), List.of("eng-code-review"));

            assertThat(context.getTools()).containsExactly("pullTasks", "submitResult", "web_search");
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> toolsCaptor = ArgumentCaptor.forClass(List.class);
            verify(toolRegistry).resolve(toolsCaptor.capture());
            assertThat(toolsCaptor.getValue()).containsExactly("pullTasks", "submitResult", "web_search");
        }

        @Test
        @DisplayName("checkpoint 回调装配：ctx 携带 listener，触发即转 saveLoopCheckpoint(subTaskId, turn)")
        void shouldWireLoopCheckpointListenerToSessionService() {
            AgentContext context = invokeAssemble(subTask(), agent(), List.of(), List.of());

            LoopCheckpointListener listener = context.getLoopCheckpointListener();
            assertThat(listener).isNotNull();

            LoopCheckpoint checkpoint = new LoopCheckpoint(2, 3, 6, List.of("pullTasks", "submitResult"));
            listener.onCheckpoint(checkpoint);

            verify(agentSessionService).saveLoopCheckpoint(22L, 1, checkpoint);
        }
    }

    // #endregion

    // #region 测试夹具

    private AgentContext invokeAssemble(SubTaskFixture subTask, Agent agent,
                                        List<String> tools, List<String> declaredSkills) {
        return assembler.assemble(command(declaredSkills, tools), subTask.toSnapshot(), agent, tools,
                new LocalProcessEnvironment());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureTimelinePayload(String eventType) {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(taskTimelinePort).recordEvent(any(), any(), eq(eventType), any(), any(), captor.capture());
        return captor.getValue();
    }

    private static ExecutionCommand command(List<String> declaredSkills, List<String> tools) {
        return ExecutionCommand.builder()
                .recordId(44L)
                .eventId("evt-1")
                .subTaskId(22L)
                .agentId(11L)
                .trigger("assigned")
                .accessType(AgentAccessType.API_KEY_LLM)
                .requiredSkills(declaredSkills)
                .tools(tools)
                .build();
    }

    private static SubTaskFixture subTask() {
        SubTaskFixture fixture = new SubTaskFixture();
        fixture.setId(22L);
        fixture.setTaskId(33L);
        fixture.setAssignedAgentId(11L);
        fixture.setStatus(SubTaskStatus.ASSIGNED);
        fixture.setTitle("测试子任务");
        fixture.setReworkCount(0);
        fixture.setAttemptTotal(0);
        return fixture;
    }

    private static Agent agent() {
        Agent agent = new Agent();
        agent.setId(11L);
        agent.setName("test-agent");
        agent.setAccessType(AgentAccessType.API_KEY_LLM);
        agent.setModelType("mock:helloai-mock-executor");
        return agent;
    }

    /**
     * 测试内部可变夹具：保留原 task 实体 {@code SubTask} 的同名 setter，使测试方法体零改动；
     * {@link #toSnapshot()} 以 {@link SubTaskSnapshot#builder()} 产出 agent 域不可变快照
     * （W11 端口化后装配器只接收快照，测试侧以夹具承接原实体的可变写法）。
     */
    private static final class SubTaskFixture {

        private Long id;
        private SubTaskStatus status;
        private Long taskId;
        private Long assignedAgentId;
        private Map<String, Object> context;
        private String title;
        private String content;
        private String deliverable;
        private String acceptance;
        private String constraints;
        private List<Long> dependsOn;
        private List<UncertaintySnapshot> uncertainties;
        private Integer reworkCount;
        private Integer attemptTotal;

        void setId(Long id) {
            this.id = id;
        }

        void setStatus(SubTaskStatus status) {
            this.status = status;
        }

        void setTaskId(Long taskId) {
            this.taskId = taskId;
        }

        void setAssignedAgentId(Long assignedAgentId) {
            this.assignedAgentId = assignedAgentId;
        }

        void setContext(Map<String, Object> context) {
            this.context = context;
        }

        void setTitle(String title) {
            this.title = title;
        }

        void setContent(String content) {
            this.content = content;
        }

        void setDeliverable(String deliverable) {
            this.deliverable = deliverable;
        }

        void setAcceptance(String acceptance) {
            this.acceptance = acceptance;
        }

        void setConstraints(String constraints) {
            this.constraints = constraints;
        }

        void setDependsOn(List<Long> dependsOn) {
            this.dependsOn = dependsOn;
        }

        void setUncertainties(List<UncertaintySnapshot> uncertainties) {
            this.uncertainties = uncertainties;
        }

        void setReworkCount(Integer reworkCount) {
            this.reworkCount = reworkCount;
        }

        void setAttemptTotal(Integer attemptTotal) {
            this.attemptTotal = attemptTotal;
        }

        SubTaskSnapshot toSnapshot() {
            return SubTaskSnapshot.builder()
                    .id(id)
                    .status(status)
                    .taskId(taskId)
                    .assignedAgentId(assignedAgentId)
                    .context(context)
                    .title(title)
                    .content(content)
                    .deliverable(deliverable)
                    .acceptance(acceptance)
                    .constraints(constraints)
                    .reworkCount(reworkCount)
                    .attemptTotal(attemptTotal)
                    .dependsOn(dependsOn)
                    .uncertainties(uncertainties)
                    .build();
        }
    }

    // #endregion
}
