package com.helloai.core.planner.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.SkillExecutionProperties;
import com.helloai.common.constant.SkillExecutionPhase;
import com.helloai.core.planner.entity.ChatSkillRun;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.mapper.ChatSkillRunMapper;
import com.helloai.core.planner.service.RequirementMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SkillExecutionWorker} 聚焦测试（SKX-13）。
 *
 * <p>覆盖四个必须成立的行为：① 幂等闸门（领不到就不跑）；② 成功落产物消息并置终态；
 * ③ 失败落对话消息（不是抛给 HTTP）；④ 已取消/超时的执行不落成功消息。</p>
 */
@DisplayName("SkillExecutionWorker 技能执行 worker")
class SkillExecutionWorkerTest {

    private ChatSkillRunMapper mapper;
    private ConversationSkillRunner runner;
    private RequirementMessageService messageService;
    private SkillExecutionWorker worker;

    @BeforeEach
    void setUp() {
        mapper = mock(ChatSkillRunMapper.class);
        runner = mock(ConversationSkillRunner.class);
        messageService = mock(RequirementMessageService.class);
        SkillExecutionProperties properties = new SkillExecutionProperties();
        worker = new SkillExecutionWorker(mapper, runner, messageService, properties, new ObjectMapper());
    }

    private ChatSkillRun pendingRun() {
        ChatSkillRun run = new ChatSkillRun();
        run.setId(1001L);
        run.setConversationId(2002L);
        run.setSkillName("archify");
        run.setStatus("PENDING");
        run.setTimeoutSeconds(600);
        return run;
    }

    private void stubRequestMessage() {
        RequirementMessage message = new RequirementMessage();
        message.setRole("user");
        message.setContent("画一张支付审批流程图");
        message.setPayload("{\"skill\":\"archify\"}");
        when(messageService.listByConversation(2002L)).thenReturn(List.of(message));
    }

    @Test
    @DisplayName("幂等闸门：领不到（PENDING→RUNNING 未命中）就不执行")
    void execute_notClaimed_doesNotRun() {
        when(mapper.selectById(1001L)).thenReturn(pendingRun());
        when(mapper.markRunning(1001L)).thenReturn(0);

        worker.execute(1001L);

        verify(mapper).markRunning(1001L);
        verify(runner, never()).run(anyLong(), any(), any(), anyString(), anyString(), any());
        verify(messageService, never()).addMessage(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("同会话并发被唯一索引拒绝：收尾成 FAILED（不能留在 PENDING 永久占额度）")
    void execute_claimRejected_marksFailed() {
        when(mapper.selectById(1001L)).thenReturn(pendingRun());
        when(mapper.markRunning(1001L)).thenThrow(new IllegalStateException("uk_skill_run_active"));

        worker.execute(1001L);

        verify(runner, never()).run(anyLong(), any(), any(), anyString(), anyString(), any());
        verify(mapper).markFailed(eq(1001L), anyString());
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(messageService).addMessage(eq(2002L), eq("assistant"), content.capture());
        assertThat(content.getValue()).contains("已有技能在执行");
    }

    @Test
    @DisplayName("成功：落 assistant 消息（带产物 payload）并置 SUCCEEDED")
    void execute_success_addsMessageAndMarksSucceeded() {
        when(mapper.markRunning(1001L)).thenReturn(1);
        when(mapper.selectById(1001L)).thenReturn(pendingRun());
        stubRequestMessage();
        ConversationSkillRunner.Result result = new ConversationSkillRunner.Result(
                77L, "workflow-20261030-1200.html", 819076L, "abc123", "workflow", "showcase", null);
        when(runner.run(anyLong(), any(), any(), anyString(), anyString(), any())).thenReturn(result);

        worker.execute(1001L);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(messageService).addMessage(eq(2002L), eq("assistant"), content.capture(), any());
        assertThat(content.getValue()).contains("workflow").contains("已生成");
        verify(mapper).markSucceeded(eq(1001L), eq(77L), any());
    }

    @Test
    @DisplayName("失败：落对话里的失败消息 + 置 FAILED（不抛给 HTTP）")
    void execute_failure_addsFailureMessageAndMarksFailed() {
        when(mapper.markRunning(1001L)).thenReturn(1);
        when(mapper.selectById(1001L)).thenReturn(pendingRun());
        stubRequestMessage();
        when(runner.run(anyLong(), any(), any(), anyString(), anyString(), any()))
                .thenThrow(new com.helloai.common.base.BizException("技能渲染失败：schema 不合法"));

        worker.execute(1001L);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(messageService).addMessage(eq(2002L), eq("assistant"), content.capture());
        assertThat(content.getValue()).contains("生成失败").contains("schema 不合法");
        verify(mapper).markFailed(eq(1001L), anyString());
        verify(mapper, never()).markSucceeded(anyLong(), any(), any());
    }

    @Test
    @DisplayName("已取消：执行中途中止，不落成功消息，置 CANCELLED")
    void execute_cancelled_doesNotAddSuccessMessage() {
        when(mapper.markRunning(1001L)).thenReturn(1);
        // 运行记录已被置为 CANCELLED —— worker 在阶段边界自查到就中止
        when(mapper.selectById(1001L)).thenAnswer(inv -> {
            ChatSkillRun run = pendingRun();
            run.setStatus("CANCELLED");
            return run;
        });
        stubRequestMessage();
        // 模拟真实流程：runner 在阶段边界调 control.shouldAbort()，自查到取消即抛
        when(runner.run(anyLong(), any(), any(), anyString(), anyString(), any())).thenAnswer(inv -> {
            SkillRunControl control = inv.getArgument(5);
            if (control.shouldAbort()) {
                throw new com.helloai.common.base.BizException("技能执行已中止（已取消或超过服务端时限）");
            }
            return new ConversationSkillRunner.Result(
                    78L, "workflow.html", 100L, "sha", "workflow", "showcase", null);
        });

        worker.execute(1001L);

        verify(messageService, never()).addMessage(anyLong(), eq("assistant"), anyString(), any());
        verify(mapper).markCancelled(eq(1001L));
        verify(mapper, never()).markSucceeded(anyLong(), any(), any());
        verify(mapper, never()).markFailed(anyLong(), anyString());
    }

    @Test
    @DisplayName("阶段推进：runner 通过 control 上报阶段后写回运行记录")
    void execute_reportsPhase() {
        when(mapper.markRunning(1001L)).thenReturn(1);
        when(mapper.selectById(1001L)).thenReturn(pendingRun());
        stubRequestMessage();
        // 阶段由 runner 在内部上报（runner 内部是真实实现时才有阶段），
        // 这里验证的是「control.onPhase → markPhase」这条接线
        when(runner.run(anyLong(), any(), any(), anyString(), anyString(), any())).thenAnswer(inv -> {
            SkillRunControl control = inv.getArgument(5);
            control.onPhase(SkillExecutionPhase.RENDER);
            return new ConversationSkillRunner.Result(
                    78L, "workflow.html", 100L, "sha", "workflow", "showcase", null);
        });

        worker.execute(1001L);

        verify(mapper).markPhase(eq(1001L), eq("RENDER"), anyString());
    }

    @Test
    @DisplayName("找不到需求正文：判失败并落错误，不执行技能")
    void execute_missingNeed_marksFailed() {
        when(mapper.markRunning(1001L)).thenReturn(1);
        when(mapper.selectById(1001L)).thenReturn(pendingRun());
        when(messageService.listByConversation(2002L)).thenReturn(List.of());

        worker.execute(1001L);

        verify(runner, never()).run(anyLong(), any(), any(), anyString(), anyString(), any());
        verify(mapper).markFailed(eq(1001L), anyString());
    }
}
