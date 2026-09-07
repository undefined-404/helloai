package com.helloai.core.task.service.impl;

import com.helloai.core.agent.event.AgentEventQueryService;
import com.helloai.core.agent.event.AgentEventTraceItem;
import com.helloai.core.task.entity.TaskTimeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * A6 收口：task_timeline + agent_event 合并时间线测试（与实现同包，直接测 merge / toTimeline）。
 *
 * <p>验证并轨语义：粗粒度业务事件与细粒度执行轨迹按 createTime ASC（同刻按 id ASC）合并有序；
 * agent_event 条目映射为 TaskTimeline（id 用 agent_event 主键、role 缺省 null）。
 * 粗查询部分由 MyBatis-Plus 框架保证，不在此 mock 覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
class TaskTimelineServiceImplTest {

    @Mock
    private AgentEventQueryService agentEventQueryService;

    private TaskTimelineServiceImpl newService() {
        // baseMapper 由 Spring 注入；本测试只触 merge / toTimeline / null 分支，无需 mapper
        return new TaskTimelineServiceImpl(agentEventQueryService);
    }

    @Test
    @DisplayName("subTaskId 为空 → 空集合且不触达任何查询")
    void shouldReturnEmptyWhenSubTaskIdNull() {
        TaskTimelineServiceImpl svc = newService();
        assertThat(svc.listBySubTaskId(null)).isEmpty();
        verifyNoInteractions(agentEventQueryService);
    }

    @Test
    @DisplayName("合并时间线：两类事件按 createTime ASC 交错有序，同刻按 id ASC")
    void shouldMergeAndOrderByCreateTimeThenId() {
        TaskTimelineServiceImpl svc = newService();

        // task_timeline 粗事件（id 小）
        TaskTimeline tl1 = timeline(1L, "sub_task_dispatch_prepare", OffsetDateTime.parse("2026-09-06T10:00:00Z"));
        TaskTimeline tl2 = timeline(2L, "sub_task_execute_submit", OffsetDateTime.parse("2026-09-06T10:30:00Z"));
        // agent_event 细轨迹（id 大，雪花），时间交错；e3 与 tl1 同刻但 id 更大
        TaskTimeline e1 = svc.toTimeline(traceItem(100L, "agent_started", OffsetDateTime.parse("2026-09-06T10:10:00Z")));
        TaskTimeline e2 = svc.toTimeline(traceItem(200L, "tool_call_completed", OffsetDateTime.parse("2026-09-06T10:20:00Z")));
        TaskTimeline e3 = svc.toTimeline(traceItem(300L, "context_built", OffsetDateTime.parse("2026-09-06T10:00:00Z")));

        List<TaskTimeline> merged = svc.merge(List.of(tl1, tl2), List.of(e1, e2, e3));

        assertThat(merged).extracting(TaskTimeline::getId)
                .containsExactly(1L, 300L, 100L, 200L, 2L);
        assertThat(merged).extracting(TaskTimeline::getEventType)
                .containsExactly("sub_task_dispatch_prepare", "context_built", "agent_started",
                        "tool_call_completed", "sub_task_execute_submit");
    }

    @Test
    @DisplayName("agent_event 条目映射：eventType/agentId/payload/taskId 保留，role 缺省 null")
    void shouldProjectTraceItemToTimeline() {
        TaskTimelineServiceImpl svc = newService();
        AgentEventTraceItem ev = traceItem(500L, "skill_resolved", OffsetDateTime.parse("2026-09-06T10:05:00Z"));
        ev = AgentEventTraceItem.builder()
                .id(500L)
                .runId("run-1-1")
                .taskId(10L)
                .subTaskId(10L)
                .turn(1)
                .step(5)
                .eventType("skill_resolved")
                .agentId(7L)
                .payload(Map.of("skills", "web-search"))
                .createTime(OffsetDateTime.parse("2026-09-06T10:05:00Z"))
                .build();

        TaskTimeline t = svc.toTimeline(ev);

        assertThat(t.getId()).isEqualTo(500L);
        assertThat(t.getTaskId()).isEqualTo(10L);
        assertThat(t.getSubTaskId()).isEqualTo(10L);
        assertThat(t.getEventType()).isEqualTo("skill_resolved");
        assertThat(t.getAgentId()).isEqualTo(7L);
        assertThat(t.getPayload()).containsEntry("skills", "web-search");
        assertThat(t.getRole()).isNull();
        assertThat(t.getCreateTime()).isEqualTo(OffsetDateTime.parse("2026-09-06T10:05:00Z"));
    }

    @Test
    @DisplayName("仅有一类事件时也正常合并（单侧为空）")
    void shouldMergeWhenOneSideEmpty() {
        TaskTimelineServiceImpl svc = newService();
        TaskTimeline tl = timeline(9L, "sub_task_execute_submit", OffsetDateTime.parse("2026-09-06T11:00:00Z"));
        assertThat(svc.merge(List.of(tl), List.of()))
                .extracting(TaskTimeline::getEventType)
                .containsExactly("sub_task_execute_submit");
        assertThat(svc.merge(List.of(), List.of(tl)))
                .extracting(TaskTimeline::getEventType)
                .containsExactly("sub_task_execute_submit");
    }

    private TaskTimeline timeline(Long id, String eventType, OffsetDateTime createTime) {
        TaskTimeline t = new TaskTimeline();
        t.setId(id);
        t.setSubTaskId(10L);
        t.setEventType(eventType);
        t.setCreateTime(createTime);
        return t;
    }

    private AgentEventTraceItem traceItem(Long id, String eventType, OffsetDateTime createTime) {
        return AgentEventTraceItem.builder()
                .id(id)
                .runId("run-1-1")
                .taskId(10L)
                .subTaskId(10L)
                .turn(1)
                .step(0)
                .eventType(eventType)
                .agentId(7L)
                .payload(Map.of())
                .createTime(createTime)
                .build();
    }
}