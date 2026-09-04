package com.helloai.core.agent.execution;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentEventType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.service.AttachmentService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.task.spec.ExecutionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.helloai.core.agent.command.ExecutionResultHandler;
import com.helloai.core.agent.quality.service.AgentQualityProfileService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ConversationService;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.agent.service.impl.SubTaskExecutionServiceImpl;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import com.helloai.core.task.service.TaskRunningSpecService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubTaskExecutionService")
class SubTaskExecutionServiceTest {

    @Mock
    private SubTaskService subTaskService;

    @Mock
    private AgentService agentService;

    @Mock
    private PlatformAgentExecutionService platformAgentExecutionService;

    @Mock
    private TaskTimelineService taskTimelineService;

    @Mock
    private ExecutionResultHandler executionResultHandler;

    @Mock
    private TaskRunningSpecService taskRunningSpecService;

    @Mock
    private AgentSkillSpecService agentSkillSpecService;  // Phase 1 Step 1 fix：eng-* 规范库解析（resolve(requiredSkills) 装箱传入）

    @Mock
    private ConversationService conversationService;  // §6.41

    @Mock
    private AttachmentService attachmentService;  // 依赖产出双轨：物化附件内容

    @Mock
    private AgentQualityProfileService agentQualityProfileService;  // 反馈回路第 2 层：历史表现节注入

    /** Phase 1 T2：事件记录器（用于 SKILL_RESOLVED 埋点断言；Phase 0 B2 已落执行链）。 */
    @Mock
    private AgentEventRecorder agentEventRecorder;

    @InjectMocks
    private SubTaskExecutionServiceImpl subTaskExecutionService;

    /**
     * Phase 1 T2 + Step 1 fix：默认 stub agentSkillSpecService.resolve() 返回空 ResolvedSpec，
     * 覆盖所有 executeOnce 调用路径（生产代码对 null 防御兜底）。lenient 避免 L135 状态守卫
     * 异常用例走不到 resolve 时报 UnnecessaryStubbing。
     */
    @BeforeEach
    void setUpResolvedSpec() {
        lenient().when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), ""));
    }

    @Nested
    @DisplayName("executeOnce — 纯执行入口")
    class ExecuteOnce {

        @Test
        @DisplayName("should not call startIfNeeded when executeOnce runs")
        void shouldNotCallStartIfNeededWhenExecuteOnceRuns() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            Agent agent = agent();

            AgentResult ok = AgentResult.builder().success(true).build();
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(ok);

            AgentResult result = subTaskExecutionService.executeOnce(subTask, agent, List.of());

            assertThat(result).isSameAs(ok);
            // 纯执行：不应调用状态推进、不应回写
            verify(subTaskService, never()).start(any());
            verify(executionResultHandler, never()).handleSuccess(any(), any(), any());
            verify(executionResultHandler, never()).handleFailure(any(), any(), any());
        }

        @Test
        @DisplayName("should propagate exception without calling handleFailure when executeOnce throws")
        void shouldPropagateExceptionWithoutHandleFailureWhenExecuteOnceThrows() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            Agent agent = agent();

            RuntimeException root = new RuntimeException("llm down");
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenThrow(root);

            assertThatThrownBy(() -> subTaskExecutionService.executeOnce(subTask, agent, List.of()))
                    .isSameAs(root);

            // 纯执行：异常直接传播，不应回写
            verify(executionResultHandler, never()).handleFailure(any(), any(), any());
        }

        @Test
        @DisplayName("should reject when subTask status is DONE")
        void shouldRejectWhenSubTaskStatusIsDone() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.DONE);
            Agent agent = agent();

            assertThatThrownBy(() -> subTaskExecutionService.executeOnce(subTask, agent, List.of()))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不可执行");
        }
    }

    @Nested
    @DisplayName("executeOnce — SKILL_RESOLVED 埋点（Phase 1 T2，D1=B）")
    class SkillResolved {

        @Test
        @DisplayName("should record SKILL_RESOLVED step=5 with requiredSkills + resolvedSpecs payload")
        void shouldRecordSkillResolvedStep5WithBothPayloadFields() {
            // D1=B payload：声明非空 + 命中非空，覆盖正常路径
            AgentSkillSpecService.ResolvedSpec resolved = new AgentSkillSpecService.ResolvedSpec(
                    List.of("eng-code-review", "eng-doc-standard"),
                    List.of("eng-code-review"),
                    "## 平台技能规范\n### eng-code-review\n速览...");
            // 覆盖 @BeforeEach 默认 stub 为非空值（同名 stub 后置覆盖）
            lenient().when(agentSkillSpecService.resolve(any())).thenReturn(resolved);

            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            Agent agent = agent();

            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            subTaskExecutionService.executeOnce(subTask, agent, List.of());

            // 过滤 executeOnce 链路上的 5 次 recordEventSafely 调用，只断言 SKILL_RESOLVED 埋点（step=5 + type=SKILL_RESOLVED + payload 两键）
            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(agentEventRecorder, atLeastOnce()).record(
                    anyString(), any(), any(), anyInt(),
                    eq(5), eq(AgentEventType.SKILL_RESOLVED), any(),
                    payloadCaptor.capture());
            assertThat(payloadCaptor.getValue())
                    .containsEntry("requiredSkills", List.of("eng-code-review", "eng-doc-standard"))
                    .containsEntry("resolvedSpecs", List.of("eng-code-review"));
        }

        @Test
        @DisplayName("should record SKILL_RESOLVED step=5 even when requiredSkills is empty (D1=B 恒发纪律)")
        void shouldRecordSkillResolvedEvenWhenRequiredSkillsEmpty() {
            // @BeforeEach 已 stub 空 ResolvedSpec；本用例显式覆盖以确认 requiredSkills 为空时仍发
            AgentSkillSpecService.ResolvedSpec emptyResolved = new AgentSkillSpecService.ResolvedSpec(
                    List.of(), List.of(), "");
            lenient().when(agentSkillSpecService.resolve(any())).thenReturn(emptyResolved);

            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            Agent agent = agent();

            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            subTaskExecutionService.executeOnce(subTask, agent, List.of());

            // 只断言 SKILL_RESOLVED 埋点（requiredSkills 为空时仍恒发，payload 两键值为空数组）
            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(agentEventRecorder, atLeastOnce()).record(
                    anyString(), any(), any(), anyInt(),
                    eq(5), eq(AgentEventType.SKILL_RESOLVED), any(),
                    payloadCaptor.capture());
            assertThat(payloadCaptor.getValue())
                    .containsEntry("requiredSkills", List.of())
                    .containsEntry("resolvedSpecs", List.of());
        }

        @Test
        @DisplayName("should fall back to empty ResolvedSpec when agentSkillSpecService.resolve returns null (防御非 best-effort 实现)")
        void shouldFallBackWhenResolveReturnsNull() {
            // 覆盖为 null 模拟非 best-effort 实现；production 代码 null 防御
            lenient().when(agentSkillSpecService.resolve(any())).thenReturn(null);

            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            Agent agent = agent();

            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            // 不应抛 NPE
            subTaskExecutionService.executeOnce(subTask, agent, List.of());

            // 仍埋 SKILL_RESOLVED（payload 两键恒在，值为空数组）
            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(agentEventRecorder, atLeastOnce()).record(
                    anyString(), any(), any(), anyInt(),
                    eq(5), eq(AgentEventType.SKILL_RESOLVED), any(),
                    payloadCaptor.capture());
            assertThat(payloadCaptor.getValue())
                    .containsEntry("requiredSkills", List.of())
                    .containsEntry("resolvedSpecs", List.of());
        }
    }

    @Nested
    @DisplayName("executeCommand — 完整编排入口（向后兼容）")
    class ExecuteCommand {

        @Test
        @DisplayName("should call startIfNeeded + handleFailure when executeSync throws")
        void shouldPropagateOriginalExceptionWhenExecuteSyncThrows() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.ASSIGNED);
            Agent agent = agent();

            RuntimeException root = new RuntimeException();
            when(subTaskService.getById(22L)).thenReturn(subTask);
            when(agentService.getById(44L)).thenReturn(agent);
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenThrow(root);

            ExecutionCommand command = ExecutionCommand.builder()
                    .subTaskId(22L)
                    .agentId(44L)
                    .trigger("test")
                    .build();

            assertThatThrownBy(() -> subTaskExecutionService.executeCommand(command))
                    .isSameAs(root);

            verify(subTaskService).start(22L);
            verify(executionResultHandler).handleFailure(22L, 44L, root);
        }

        @Test
        @DisplayName("should throw BizException when agentId mismatch")
        void shouldThrowWhenAgentIdMismatch() {
            SubTask subTask = subTask();
            subTask.setAssignedAgentId(44L);

            when(subTaskService.getById(22L)).thenReturn(subTask);

            ExecutionCommand command = ExecutionCommand.builder()
                    .subTaskId(22L)
                    .agentId(99L)
                    .trigger("test")
                    .build();

            assertThatThrownBy(() -> subTaskExecutionService.executeCommand(command))
                    .isInstanceOf(BizException.class);
        }

        @Test
        @DisplayName("should throw BizException when agentId is null")
        void shouldThrowWhenCommandAgentIdIsNull() {
            ExecutionCommand command = ExecutionCommand.builder()
                    .subTaskId(22L)
                    .agentId(null)
                    .trigger("test")
                    .build();

            assertThatThrownBy(() -> subTaskExecutionService.executeCommand(command))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("agentId");
        }
    }

    @Nested
    @DisplayName("executeOnce — 依赖产出双轨上下文注入（Task Running Spec）")
    class ExecuteOnceDependencyContext {

        @Test
        @DisplayName("should not query deps when subTask has no dependsOn")
        void shouldNotQueryDepsWhenNoDependsOn() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            Agent agent = agent();

            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            subTaskExecutionService.executeOnce(subTask, agent, List.of());

            // 无依赖：零注入，不查前置、不查附件
            verify(subTaskService, never()).listByIds(any());
            verify(attachmentService, never()).list(any(Long.class));
            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            assertThat(taskCaptor.getValue().getUserPrompt())
                    .doesNotContain("依赖产出参考");
            // 装配事实仍可观测：depCount=0
            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(taskTimelineService).recordEvent(eq(33L), eq(22L), eq("sub_task_spec_context_loaded"),
                    eq(AgentRole.EXECUTOR), eq(44L), payloadCaptor.capture());
            assertThat(payloadCaptor.getValue())
                    .containsEntry("depCount", 0)
                    .containsEntry("degraded", false);
        }

        @Test
        @DisplayName("should inject summary and full content for single dep")
        void shouldInjectSummaryAndContentForSingleDep() {
            SubTask upstream = subTask();
            upstream.setId(11L);
            upstream.setTitle("调研竞品");
            upstream.setStatus(SubTaskStatus.DONE);
            upstream.setContext(Map.of("lastExecution", Map.of("output", "竞品清单：A/B/C")));

            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            subTask.setDependsOn(List.of(11L));
            Agent agent = agent();

            when(subTaskService.listByIds(List.of(11L))).thenReturn(List.of(upstream));
            when(taskRunningSpecService.findRecord(33L, 11L))
                    .thenReturn(ExecutionRecord.builder()
                            .subTaskId(11L).title("调研竞品").summary("完成竞品调研，产出清单").build());
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            subTaskExecutionService.executeOnce(subTask, agent, List.of());

            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            String prompt = taskCaptor.getValue().getUserPrompt();
            assertThat(prompt)
                    .contains("## 依赖产出参考（直接前置）")
                    .contains("### 前置 1：调研竞品（状态：DONE）")
                    .contains("**产出摘要**: 完成竞品调研，产出清单")
                    .contains("**内容**:")
                    .contains("竞品清单：A/B/C");
            // 声明了依赖 → 记录装配统计
            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(taskTimelineService).recordEvent(eq(33L), eq(22L), eq("sub_task_spec_context_loaded"),
                    eq(AgentRole.EXECUTOR), eq(44L), payloadCaptor.capture());
            assertThat(payloadCaptor.getValue())
                    .containsEntry("depCount", 1)
                    .containsEntry("loadedCount", 1)
                    .containsEntry("degraded", false);
        }

        @Test
        @DisplayName("should inject BOTH predecessors when subTask has multiple deps (no overwrite)")
        void shouldInjectBothPredecessorsWhenMultipleDeps() {
            SubTask upstreamA = subTask();
            upstreamA.setId(11L);
            upstreamA.setTitle("调研竞品");
            upstreamA.setStatus(SubTaskStatus.DONE);
            upstreamA.setContext(Map.of("lastExecution", Map.of("output", "竞品清单：A/B/C")));

            SubTask upstreamB = subTask();
            upstreamB.setId(12L);
            upstreamB.setTitle("调研目标用户");
            upstreamB.setStatus(SubTaskStatus.DONE);
            upstreamB.setContext(Map.of("lastExecution", Map.of("output", "目标用户画像：白领/学生")));

            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            subTask.setDependsOn(List.of(11L, 12L));
            Agent agent = agent();

            when(subTaskService.listByIds(List.of(11L, 12L))).thenReturn(List.of(upstreamA, upstreamB));
            when(taskRunningSpecService.findRecord(33L, 11L))
                    .thenReturn(ExecutionRecord.builder().subTaskId(11L).title("调研竞品")
                            .summary("完成竞品调研，产出清单").build());
            when(taskRunningSpecService.findRecord(33L, 12L))
                    .thenReturn(ExecutionRecord.builder().subTaskId(12L).title("调研目标用户")
                            .summary("完成用户调研，产出画像").build());
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            subTaskExecutionService.executeOnce(subTask, agent, List.of());

            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            String prompt = taskCaptor.getValue().getUserPrompt();
            // 多前置并存：两条前置的内容必须同时出现在 prompt 中（防"只留最后一个"回归）
            assertThat(prompt)
                    .contains("### 前置 1：调研竞品（状态：DONE）")
                    .contains("### 前置 2：调研目标用户（状态：DONE）")
                    .contains("竞品清单：A/B/C")
                    .contains("目标用户画像：白领/学生");
            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(taskTimelineService).recordEvent(eq(33L), eq(22L), eq("sub_task_spec_context_loaded"),
                    eq(AgentRole.EXECUTOR), eq(44L), payloadCaptor.capture());
            assertThat(payloadCaptor.getValue())
                    .containsEntry("depCount", 2)
                    .containsEntry("loadedCount", 2);
        }

        @Test
        @DisplayName("should prefer materialized attachment content over raw output")
        void shouldPreferAttachmentContentOverRawOutput() {
            Attachment attachment = new Attachment();
            attachment.setId(99L);

            SubTask upstream = subTask();
            upstream.setId(11L);
            upstream.setTitle("调研竞品");
            upstream.setStatus(SubTaskStatus.DONE);
            upstream.setContext(Map.of("lastExecution", Map.of("output", "旧版原始产出")));

            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            subTask.setDependsOn(List.of(11L));
            Agent agent = agent();

            when(subTaskService.listByIds(List.of(11L))).thenReturn(List.of(upstream));
            when(attachmentService.listActive(11L)).thenReturn(List.of(attachment));
            when(attachmentService.isContentLoadable(attachment)).thenReturn(true);
            when(attachmentService.loadContent(99L)).thenReturn("物化附件正文：竞品对比表".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            subTaskExecutionService.executeOnce(subTask, agent, List.of());

            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            assertThat(taskCaptor.getValue().getUserPrompt())
                    .contains("物化附件正文：竞品对比表")
                    .doesNotContain("旧版原始产出");
        }

        @Test
        @DisplayName("should fallback to raw output when attachment read fails")
        void shouldFallbackToRawOutputWhenAttachmentReadFails() {
            Attachment attachment = new Attachment();
            attachment.setId(99L);

            SubTask upstream = subTask();
            upstream.setId(11L);
            upstream.setTitle("调研竞品");
            upstream.setStatus(SubTaskStatus.DONE);
            upstream.setContext(Map.of("lastExecution", Map.of("output", "回退原始产出：竞品清单")));

            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            subTask.setDependsOn(List.of(11L));
            Agent agent = agent();

            when(subTaskService.listByIds(List.of(11L))).thenReturn(List.of(upstream));
            when(attachmentService.listActive(11L)).thenReturn(List.of(attachment));
            when(attachmentService.isContentLoadable(attachment)).thenReturn(true);
            when(attachmentService.loadContent(99L)).thenThrow(new RuntimeException("file missing"));
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            subTaskExecutionService.executeOnce(subTask, agent, List.of());

            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            assertThat(taskCaptor.getValue().getUserPrompt())
                    .contains("回退原始产出：竞品清单");
        }

        @Test
        @DisplayName("should render placeholder when upstream is DONE but has no content")
        void shouldRenderPlaceholderWhenUpstreamHasNoOutput() {
            SubTask upstream = subTask();
            upstream.setId(11L);
            upstream.setStatus(SubTaskStatus.DONE);
            upstream.setContext(null);

            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            subTask.setDependsOn(List.of(11L));
            Agent agent = agent();

            when(subTaskService.listByIds(List.of(11L))).thenReturn(List.of(upstream));
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            subTaskExecutionService.executeOnce(subTask, agent, List.of());

            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            assertThat(taskCaptor.getValue().getUserPrompt())
                    .contains("（该前置子任务无可用产出内容）");
        }

        @Test
        @DisplayName("should truncate oversized upstream content with explicit mark")
        void shouldTruncateOversizedUpstreamOutput() {
            String longOutput = "X".repeat(5000);
            SubTask upstream = subTask();
            upstream.setId(11L);
            upstream.setStatus(SubTaskStatus.DONE);
            upstream.setContext(Map.of("lastExecution", Map.of("output", longOutput)));

            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            subTask.setDependsOn(List.of(11L));
            Agent agent = agent();

            when(subTaskService.listByIds(List.of(11L))).thenReturn(List.of(upstream));
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            subTaskExecutionService.executeOnce(subTask, agent, List.of());

            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            assertThat(taskCaptor.getValue().getUserPrompt())
                    .contains("已截断至 4000 字符")
                    .doesNotContain("X".repeat(4999));
            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(taskTimelineService).recordEvent(eq(33L), eq(22L), eq("sub_task_spec_context_loaded"),
                    eq(AgentRole.EXECUTOR), eq(44L), payloadCaptor.capture());
            assertThat(payloadCaptor.getValue())
                    .containsEntry("truncatedCount", 1);
        }

        @Test
        @DisplayName("should degrade gracefully and keep executing when dep query fails")
        void shouldDegradeGracefullyWhenDepQueryFails() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            subTask.setDependsOn(List.of(11L));
            Agent agent = agent();

            when(subTaskService.listByIds(any())).thenThrow(new RuntimeException("db down"));
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            AgentResult result = subTaskExecutionService.executeOnce(subTask, agent, List.of());

            assertThat(result.isSuccess()).isTrue();
            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            // 降级：不注入依赖段，行为与旧版一致
            assertThat(taskCaptor.getValue().getUserPrompt()).doesNotContain("依赖产出参考");
            // 但仍需可观测：degraded=true 进 timeline
            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(taskTimelineService).recordEvent(eq(33L), eq(22L), eq("sub_task_spec_context_loaded"),
                    eq(AgentRole.EXECUTOR), eq(44L), payloadCaptor.capture());
            assertThat(payloadCaptor.getValue())
                    .containsEntry("depCount", 1)
                    .containsEntry("degraded", true);
        }
    }

    @Nested
    @DisplayName("startIfNeeded — 状态推进前置")
    class StartIfNeeded {

        @Test
        @DisplayName("should skip when status is IN_PROGRESS")
        void shouldSkipWhenStatusInProgress() {
            subTaskExecutionService.startIfNeeded(22L, SubTaskStatus.IN_PROGRESS);
            verify(subTaskService, never()).start(any());
        }

        @Test
        @DisplayName("should call subTaskService.start when status is ASSIGNED")
        void shouldCallStartWhenAssigned() {
            subTaskExecutionService.startIfNeeded(22L, SubTaskStatus.ASSIGNED);
            verify(subTaskService).start(22L);
        }

        @Test
        @DisplayName("should call subTaskService.start when status is REWORK")
        void shouldCallStartWhenRework() {
            subTaskExecutionService.startIfNeeded(22L, SubTaskStatus.REWORK);
            verify(subTaskService).start(22L);
        }

        @Test
        @DisplayName("should call subTaskService.start when status is PAUSED")
        void shouldCallStartWhenPaused() {
            subTaskExecutionService.startIfNeeded(22L, SubTaskStatus.PAUSED);
            verify(subTaskService).start(22L);
        }

        @Test
        @DisplayName("should throw when status is BLOCKED")
        void shouldThrowWhenStatusBlocked() {
            assertThatThrownBy(() -> subTaskExecutionService.startIfNeeded(22L, SubTaskStatus.BLOCKED))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不允许执行");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  §6.41 executeOnce — 执行请求对话流 user prompt 落库 + reviewHistory 多轮铺开
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("executeOnce — §6.41 执行请求对话流 + reviewHistory 多轮铺开")
    class ExecuteOnceUserPromptAndReworkHistory {

        @Test
        @DisplayName("TC-1 should write user prompt to conversation stream with sub_task_execute_user_prompt")
        void shouldWriteUserPromptToConversationStream() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            Agent agent = agent();

            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            subTaskExecutionService.executeOnce(subTask, agent, List.of());

            verify(conversationService).addMessage(
                    eq(22L), eq(44L),
                    eq("user"), eq("agent"),
                    anyString(),
                    eq("sub_task_execute_user_prompt"));
        }

        @Test
        @DisplayName("TC-2 should keep user prompt in stream when executeSync throws")
        void shouldKeepUserPromptWhenExecuteSyncThrows() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            Agent agent = agent();

            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenThrow(new RuntimeException("llm down"));

            assertThatThrownBy(() -> subTaskExecutionService.executeOnce(subTask, agent, List.of()))
                    .hasMessage("llm down");

            // 失败路径：prompt 仍落库（先 addMessage 后 executeSync）
            verify(conversationService).addMessage(
                    eq(22L), eq(44L),
                    eq("user"), eq("agent"),
                    anyString(),
                    eq("sub_task_execute_user_prompt"));
        }

        @Test
        @DisplayName("TC-3 should render review history with multiple rounds")
        void shouldRenderReviewHistoryWithMultipleRounds() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("reviewHistory", List.of(
                    Map.of("round", 1, "ts", "2026-08-01T10:00:00Z",
                            "issues", List.of("缺端点"), "comment", "请补", "score", 2),
                    Map.of("round", 2, "ts", "2026-08-01T11:00:00Z",
                            "issues", List.of("格式不对", "示例缺失"), "comment", "再改", "score", 3)
            ));
            subTask.setContext(ctx);
            Agent agent = agent();

            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            subTaskExecutionService.executeOnce(subTask, agent, List.of());

            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            String prompt = taskCaptor.getValue().getUserPrompt();
            assertThat(prompt)
                    .contains("## 返工修正指引（共 2 轮历史审核）")
                    .contains("### 第 1 轮")
                    .contains("- 时间: 2026-08-01T10:00:00Z")
                    .contains("缺端点")
                    .contains("### 第 2 轮")
                    .contains("格式不对")
                    .contains("示例缺失")
                    .contains("再改")
                    .contains("请务必针对未自认修复的问题继续修正");
        }

        @Test
        @DisplayName("TC-4 should skip rework section when reviewHistory and lastAutoReview both empty")
        void shouldSkipReworkSectionWhenHistoryEmpty() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            // 不设 context，appendReworkContext 第一行 if (ctx == null) return
            Agent agent = agent();

            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            subTaskExecutionService.executeOnce(subTask, agent, List.of());

            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            assertThat(taskCaptor.getValue().getUserPrompt())
                    .doesNotContain("返工修正指引");
        }

        @Test
        @DisplayName("TC-5 should inject rework section from legacy lastAutoReview when reviewHistory absent")
        void shouldInjectReworkFromLegacyLastAutoReview() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("lastAutoReview", Map.of("issues", "缺端点", "comment", "请补", "score", 2));
            subTask.setContext(ctx);
            Agent agent = agent();

            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            subTaskExecutionService.executeOnce(subTask, agent, List.of());

            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            String prompt = taskCaptor.getValue().getUserPrompt();
            assertThat(prompt)
                    .contains("## 返工修正指引（共 1 轮历史审核）")
                    .contains("### 第 1 轮")
                    .contains("缺端点");
        }
    }

    @Nested
    @DisplayName("executeOnce — 历史表现注入（反馈回路第 2 层）")
    class ExecuteOnceHistorySection {

        @Test
        @DisplayName("TC-1 should inject history section into prompt when profile summary non-blank")
        void shouldInjectHistorySectionWhenProfileExists() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            Agent agent = agent();

            when(agentQualityProfileService.renderHistorySection(44L))
                    .thenReturn("## 你的历史表现\n- 累计评审 5 次，通过率 80%\n"
                            + "- 最常见驳回原因 TOP1：交付物未物化\n"
                            + "- 本轮提醒：请对照验收标准逐条自查\n");
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            subTaskExecutionService.executeOnce(subTask, agent, List.of());

            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            assertThat(taskCaptor.getValue().getUserPrompt())
                    .contains("## 你的历史表现")
                    .contains("累计评审 5 次")
                    .contains("最常见驳回原因 TOP1");
            // 装配观测：historySummary=true
            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(taskTimelineService).recordEvent(eq(33L), eq(22L), eq("sub_task_spec_context_loaded"),
                    eq(AgentRole.EXECUTOR), eq(44L), payloadCaptor.capture());
            assertThat(payloadCaptor.getValue()).containsEntry("historySummary", true);
        }

        @Test
        @DisplayName("TC-2 should omit history section when profile summary blank")
        void shouldOmitHistorySectionWhenProfileBlank() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            Agent agent = agent();

            when(agentQualityProfileService.renderHistorySection(44L)).thenReturn("");
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            subTaskExecutionService.executeOnce(subTask, agent, List.of());

            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            assertThat(taskCaptor.getValue().getUserPrompt()).doesNotContain("你的历史表现");
            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(taskTimelineService).recordEvent(eq(33L), eq(22L), eq("sub_task_spec_context_loaded"),
                    eq(AgentRole.EXECUTOR), eq(44L), payloadCaptor.capture());
            assertThat(payloadCaptor.getValue()).containsEntry("historySummary", false);
        }

        @Test
        @DisplayName("TC-3 should degrade gracefully and keep executing when history render throws")
        void shouldDegradeWhenHistoryRenderThrows() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            Agent agent = agent();

            when(agentQualityProfileService.renderHistorySection(44L))
                    .thenThrow(new RuntimeException("profile db down"));
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            AgentResult result = subTaskExecutionService.executeOnce(subTask, agent, List.of());

            assertThat(result.isSuccess()).isTrue();
            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            assertThat(taskCaptor.getValue().getUserPrompt()).doesNotContain("你的历史表现");
            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(taskTimelineService).recordEvent(eq(33L), eq(22L), eq("sub_task_spec_context_loaded"),
                    eq(AgentRole.EXECUTOR), eq(44L), payloadCaptor.capture());
            assertThat(payloadCaptor.getValue()).containsEntry("historySummary", false);
        }
    }

    private static SubTask subTask() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(33L);
        subTask.setAssignedAgentId(44L);
        subTask.setTitle("demo");
        subTask.setContent("demo content");
        return subTask;
    }

    private static Agent agent() {
        Agent agent = new Agent();
        agent.setId(44L);
        agent.setName("test-agent");
        return agent;
    }
}