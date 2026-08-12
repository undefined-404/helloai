package com.helloai.core.agent.mcp;

import com.helloai.common.constant.AgentDutyLeaseStatus;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.command.ExecutionResultHandler;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.entity.AgentDutyLease;
import com.helloai.core.agent.entity.AgentInbox;
import com.helloai.core.agent.observability.HeartbeatService;
import com.helloai.core.agent.service.AgentDutyLeaseService;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentMcpServerService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.system.entity.Attachment;
import com.helloai.core.system.service.AttachmentService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.spec.ExecutionRecord;
import com.helloai.core.task.spec.TaskRunningSpecService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A0-1（§6.60）pullTasks 撤销标记单元测试：
 * 曾分配给我但已转移的子任务打 reassigned=true + currentAgentId，未转移不打标。
 * <p>A0-4（§6.63）：pullTasks includeRead/summary/read 透传 + getDepsSummary 依赖产出摘要。</p>
 * <p>A0-6（§6.65）：checkIn 租约信息透传 / checkOut 幂等三态（CLOSED/EXPIRED/NONE）/ heartbeat 剩余 TTL。</p>
 * <p>A0-7（§6.66）：pullTasks deadline 透传为 ISO8601 带时区偏移（Z 或 ±HH:MM），null=无时限。</p>
 * <p>A0-8（§6.67）：除 checkIn/checkOut 外任一工具调用自动续租——有 ACTIVE 租约时按原 TTL
 * 延长 expire_time（renewLease），无租约不自动打卡；续租失败不阻断工具调用。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("McpToolService 值班租约与收件箱工具（A0-1/A0-4/A0-6）")
class McpToolServiceTest {

    private static final long AGENT_ID = 1L;
    private static final long SUB_TASK_ID = 5L;
    private static final long OTHER_AGENT = 999L;

    @Mock private AgentService agentService;
    @Mock private AgentInboxService agentInboxService;
    @Mock private AgentMcpServerService agentMcpServerService;
    @Mock private SubTaskService subTaskService;
    @Mock private SubTaskMapper subTaskMapper;
    @Mock private HeartbeatService heartbeatService;
    @Mock private AttachmentService attachmentService;
    @Mock private ExecutionResultHandler executionResultHandler;
    @Mock private AgentDutyLeaseService agentDutyLeaseService;
    @Mock private TaskRunningSpecService taskRunningSpecService;

    private McpToolService mcpToolService;

    @BeforeEach
    void setUp() {
        mcpToolService = new McpToolService(
                agentService, agentInboxService, agentMcpServerService,
                subTaskService, subTaskMapper, heartbeatService,
                attachmentService, executionResultHandler, agentDutyLeaseService,
                taskRunningSpecService);

        Agent agent = new Agent();
        agent.setId(AGENT_ID);
        agent.setStatus(AgentStatus.ACTIVE);
        lenient().when(agentService.getById(AGENT_ID)).thenReturn(agent);
        lenient().when(agentMcpServerService.isToolEnabled(eq(AGENT_ID), eq("pullTasks"))).thenReturn(true);
        lenient().when(agentMcpServerService.getParamConstraints(eq(AGENT_ID), eq("pullTasks"))).thenReturn(null);
        lenient().when(agentMcpServerService.isToolEnabled(eq(AGENT_ID), eq("getDepsSummary"))).thenReturn(true);
        lenient().when(agentMcpServerService.isToolEnabled(eq(AGENT_ID), eq("checkIn"))).thenReturn(true);
        lenient().when(agentMcpServerService.isToolEnabled(eq(AGENT_ID), eq("checkOut"))).thenReturn(true);
        lenient().when(agentMcpServerService.isToolEnabled(eq(AGENT_ID), eq("heartbeat"))).thenReturn(true);
    }

    private AgentInbox subTaskInbox(Long refId) {
        AgentInbox inbox = new AgentInbox();
        inbox.setId(refId);
        inbox.setEventType("sub_task.reassigned");
        inbox.setTitle("任务已改派");
        inbox.setRefType("sub_task");
        inbox.setRefId(refId);
        inbox.setPriority("HIGH");
        return inbox;
    }

    private SubTask subTask(Long assignedAgentId) {
        SubTask subTask = new SubTask();
        subTask.setId(SUB_TASK_ID);
        subTask.setAssignedAgentId(assignedAgentId);
        return subTask;
    }

    @Test
    @DisplayName("子任务已转移给其他 Agent：reassigned=true + currentAgentId 指向新执行者")
    void shouldMarkReassignedWhenAgentChanged() {
        when(agentInboxService.getUnread(AGENT_ID, 10)).thenReturn(List.of(subTaskInbox(SUB_TASK_ID)));
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask(OTHER_AGENT));

        McpToolService.PullTasksResult result = mcpToolService.pullTasks(AGENT_ID, "EXECUTOR", 10);

        McpToolService.PullTasksResult.Message msg = result.getMessages().get(0);
        assertThat(msg.getReassigned()).isTrue();
        assertThat(msg.getCurrentAgentId()).isEqualTo(OTHER_AGENT);
        // 常规字段不受影响
        assertThat(msg.getSubTaskId()).isEqualTo(SUB_TASK_ID);
        assertThat(msg.getType()).isEqualTo("sub_task.reassigned");
    }

    @Test
    @DisplayName("子任务仍在本 Agent 名下：reassigned 保持 null（不下发标记）")
    void shouldNotMarkWhenAgentUnchanged() {
        when(agentInboxService.getUnread(AGENT_ID, 10)).thenReturn(List.of(subTaskInbox(SUB_TASK_ID)));
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask(AGENT_ID));

        McpToolService.PullTasksResult result = mcpToolService.pullTasks(AGENT_ID, "EXECUTOR", 10);

        McpToolService.PullTasksResult.Message msg = result.getMessages().get(0);
        assertThat(msg.getReassigned()).isNull();
        assertThat(msg.getCurrentAgentId()).isNull();
    }

    @Test
    @DisplayName("子任务执行者已清空（回收）：reassigned=true + currentAgentId 为 null")
    void shouldMarkReassignedWhenAgentCleared() {
        when(agentInboxService.getUnread(AGENT_ID, 10)).thenReturn(List.of(subTaskInbox(SUB_TASK_ID)));
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask(null));

        McpToolService.PullTasksResult result = mcpToolService.pullTasks(AGENT_ID, "EXECUTOR", 10);

        McpToolService.PullTasksResult.Message msg = result.getMessages().get(0);
        assertThat(msg.getReassigned()).isTrue();
        assertThat(msg.getCurrentAgentId()).isNull();
    }

    // ══════════════════════════════════════════════════════════════
    //  A0-2（§6.61）getAgentStatus：REST 别名通道工具对齐
    //  ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getAgentStatus：返回管理态/在线态字段（A0-2 REST 别名复用）")
    void shouldReturnAgentStatus() {
        Agent agent = new Agent();
        agent.setId(AGENT_ID);
        agent.setName("测试Agent");
        agent.setStatus(AgentStatus.ACTIVE);
        when(agentService.getById(AGENT_ID)).thenReturn(agent);
        when(agentMcpServerService.isToolEnabled(eq(AGENT_ID), eq("getAgentStatus"))).thenReturn(true);
        when(heartbeatService.checkOnlineStatus(agent)).thenReturn(com.helloai.common.constant.AgentOnlineStatus.ONLINE);

        McpToolService.GetAgentStatusResult result = mcpToolService.getAgentStatus(AGENT_ID);

        assertThat(result.getAgentId()).isEqualTo(AGENT_ID);
        assertThat(result.getName()).isEqualTo("测试Agent");
        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        assertThat(result.getComputedOnlineStatus()).isEqualTo("ONLINE");
        assertThat(result.getServerTime()).isNotNull();
    }

    // ══════════════════════════════════════════════════════════════
    //  A0-4（§6.63）pullTasks includeRead/summary/read 透传
    //  ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("pullTasks 默认（3 参）：仅未读，summary/read 透传且 read=false")
    void shouldReturnUnreadOnlyByDefault() {
        AgentInbox inbox = subTaskInbox(SUB_TASK_ID);
        inbox.setSummary("审查未通过，评分 3/5");
        inbox.setIsRead(0);
        when(agentInboxService.getUnread(AGENT_ID, 10)).thenReturn(List.of(inbox));
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask(AGENT_ID));

        McpToolService.PullTasksResult result = mcpToolService.pullTasks(AGENT_ID, "EXECUTOR", 10);

        McpToolService.PullTasksResult.Message msg = result.getMessages().get(0);
        assertThat(msg.getSummary()).isEqualTo("审查未通过，评分 3/5");
        assertThat(msg.getRead()).isFalse();
        // includeRead=false 不触发已读查询
        org.mockito.Mockito.verify(agentInboxService, org.mockito.Mockito.never())
                .getRecentRead(eq(AGENT_ID), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("pullTasks includeRead=true：未读优先 + 已读按 read_time 倒序补齐配额，read=true 打标")
    void shouldAppendRecentReadWhenIncludeRead() {
        AgentInbox unread = subTaskInbox(SUB_TASK_ID);
        unread.setSummary("审查未通过，评分 2/5");
        unread.setIsRead(0);
        AgentInbox read = subTaskInbox(6L);
        read.setSummary("审查通过，评分 5/5");
        read.setIsRead(1);
        when(agentInboxService.getUnread(AGENT_ID, 5)).thenReturn(List.of(unread));
        when(agentInboxService.getRecentRead(AGENT_ID, 4)).thenReturn(List.of(read));
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask(AGENT_ID));
        when(subTaskService.getById(6L)).thenReturn(subTask(AGENT_ID));

        McpToolService.PullTasksResult result = mcpToolService.pullTasks(AGENT_ID, "EXECUTOR", 5, true);

        assertThat(result.getMessages()).hasSize(2);
        McpToolService.PullTasksResult.Message first = result.getMessages().get(0);
        assertThat(first.getMessageId()).isEqualTo("inbox-" + SUB_TASK_ID);
        assertThat(first.getRead()).isFalse();
        McpToolService.PullTasksResult.Message second = result.getMessages().get(1);
        assertThat(second.getMessageId()).isEqualTo("inbox-6");
        assertThat(second.getRead()).isTrue();
        assertThat(second.getSummary()).isEqualTo("审查通过，评分 5/5");
    }

    @Test
    @DisplayName("pullTasks：deadline 透传为 ISO8601 带时区偏移，无 deadline 透传 null")
    void shouldPassDeadlineWithIsoOffset() {
        SubTask withDeadline = subTask(AGENT_ID);
        withDeadline.setDeadline(OffsetDateTime.now().plusHours(2));
        when(agentInboxService.getUnread(AGENT_ID, 10)).thenReturn(List.of(subTaskInbox(SUB_TASK_ID)));
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(withDeadline);

        McpToolService.PullTasksResult result = mcpToolService.pullTasks(AGENT_ID, "EXECUTOR", 10);

        McpToolService.PullTasksResult.Message msg = result.getMessages().get(0);
        assertThat(msg.getDeadline()).isNotNull();
        // A0-7：ISO8601 带时区偏移（Z 或 ±HH:MM 后缀），外部 Agent 按绝对时刻解析不误判
        assertThat(msg.getDeadline()).matches("\\d{4}-\\d{2}-\\d{2}T.*(Z|[+-]\\d{2}:\\d{2})$");

        // 无 deadline 时透传 null（=无时限）
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask(AGENT_ID));
        McpToolService.PullTasksResult noSla = mcpToolService.pullTasks(AGENT_ID, "EXECUTOR", 10);
        assertThat(noSla.getMessages().get(0).getDeadline()).isNull();
    }

    // ══════════════════════════════════════════════════════════════
    //  A0-4（§6.63）getDepsSummary 前置产出摘要
    //  ══════════════════════════════════════════════════════════════

    private SubTask taskWithDeps(Long id, List<Long> deps) {
        SubTask subTask = new SubTask();
        subTask.setId(id);
        subTask.setTaskId(100L);
        subTask.setTitle("当前任务");
        subTask.setDependsOn(deps);
        return subTask;
    }

    @Test
    @DisplayName("getDepsSummary：无依赖返回 depCount=0 非错误")
    void shouldReturnEmptyWhenNoDeps() {
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(taskWithDeps(SUB_TASK_ID, List.of()));

        McpToolService.GetDepsSummaryResult result = mcpToolService.getDepsSummary(AGENT_ID, SUB_TASK_ID);

        assertThat(result.getDepCount()).isZero();
        assertThat(result.getDeps()).isEmpty();
        assertThat(result.getDegraded()).isFalse();
    }

    @Test
    @DisplayName("getDepsSummary：有依赖时返回执行记录摘要 + 物化附件内容，loadedCount 正确")
    void shouldLoadDepSummaryAndContent() {
        SubTask current = taskWithDeps(SUB_TASK_ID, List.of(11L));
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(current);

        SubTask dep = new SubTask();
        dep.setId(11L);
        dep.setTitle("前置任务A");
        dep.setStatus(SubTaskStatus.DONE);
        when(subTaskService.listByIds(List.of(11L))).thenReturn(List.of(dep));

        ExecutionRecord record = ExecutionRecord.builder().subTaskId(11L).summary("已交付接口契约").build();
        when(taskRunningSpecService.findRecord(100L, 11L)).thenReturn(record);

        Attachment attachment = new Attachment();
        attachment.setId(21L);
        when(attachmentService.list(11L)).thenReturn(List.of(attachment));
        when(attachmentService.isContentLoadable(attachment)).thenReturn(true);
        when(attachmentService.loadContent(21L)).thenReturn("物化产出内容".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        McpToolService.GetDepsSummaryResult result = mcpToolService.getDepsSummary(AGENT_ID, SUB_TASK_ID);

        assertThat(result.getDepCount()).isEqualTo(1);
        assertThat(result.getLoadedCount()).isEqualTo(1);
        assertThat(result.getTruncatedCount()).isZero();
        McpToolService.GetDepsSummaryResult.DepItem item = result.getDeps().get(0);
        assertThat(item.getSubTaskId()).isEqualTo(11L);
        assertThat(item.getTitle()).isEqualTo("前置任务A");
        assertThat(item.getStatus()).isEqualTo("DONE");
        assertThat(item.getSummary()).isEqualTo("已交付接口契约");
        assertThat(item.getContent()).isEqualTo("物化产出内容");
        assertThat(item.getTruncated()).isNull();
    }

    @Test
    @DisplayName("getDepsSummary：单条内容超 4000 字符截断并打标 truncated=true")
    void shouldTruncateLongContent() {
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(taskWithDeps(SUB_TASK_ID, List.of(11L)));
        SubTask dep = new SubTask();
        dep.setId(11L);
        dep.setTitle("前置任务B");
        when(subTaskService.listByIds(List.of(11L))).thenReturn(List.of(dep));

        Attachment attachment = new Attachment();
        attachment.setId(22L);
        when(attachmentService.list(11L)).thenReturn(List.of(attachment));
        when(attachmentService.isContentLoadable(attachment)).thenReturn(true);
        when(attachmentService.loadContent(22L)).thenReturn("x".repeat(5000).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        McpToolService.GetDepsSummaryResult result = mcpToolService.getDepsSummary(AGENT_ID, SUB_TASK_ID);

        assertThat(result.getTruncatedCount()).isEqualTo(1);
        McpToolService.GetDepsSummaryResult.DepItem item = result.getDeps().get(0);
        assertThat(item.getTruncated()).isTrue();
        assertThat(item.getContent()).hasSize(4000);
    }

    @Test
    @DisplayName("getDepsSummary：收集异常降级返回 degraded=true，不阻断调用")
    void shouldDegradeOnCollectFailure() {
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(taskWithDeps(SUB_TASK_ID, List.of(11L)));
        when(subTaskService.listByIds(List.of(11L))).thenThrow(new RuntimeException("db down"));

        McpToolService.GetDepsSummaryResult result = mcpToolService.getDepsSummary(AGENT_ID, SUB_TASK_ID);

        assertThat(result.getDegraded()).isTrue();
        assertThat(result.getDeps()).isEmpty();
        assertThat(result.getDepCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("getDepsSummary：无物化附件时回退 context.lastExecution.output 原始产出")
    void shouldFallbackToExecutionOutput() {
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(taskWithDeps(SUB_TASK_ID, List.of(11L)));
        SubTask dep = new SubTask();
        dep.setId(11L);
        dep.setTitle("前置任务C");
        dep.setContext(Map.of("lastExecution", Map.of("output", "执行输出摘要")));
        when(subTaskService.listByIds(List.of(11L))).thenReturn(List.of(dep));
        when(attachmentService.list(11L)).thenReturn(List.of());

        McpToolService.GetDepsSummaryResult result = mcpToolService.getDepsSummary(AGENT_ID, SUB_TASK_ID);

        assertThat(result.getLoadedCount()).isEqualTo(1);
        assertThat(result.getDeps().get(0).getContent()).contains("执行输出摘要");
    }

    // ══════════════════════════════════════════════════════════════
    //  A0-6（§6.65）checkIn/checkOut/heartbeat 值班租约语义对称
    //  ══════════════════════════════════════════════════════════════

    private AgentDutyLease lease(Long id, AgentDutyLeaseStatus status, OffsetDateTime expireTime, String sessionId) {
        AgentDutyLease lease = new AgentDutyLease();
        lease.setId(id);
        lease.setAgentId(AGENT_ID);
        lease.setSessionId(sessionId);
        lease.setWorkMode("AUTO");
        lease.setMaxConcurrent(3);
        lease.setStatus(status);
        lease.setExpireTime(expireTime);
        return lease;
    }

    @Test
    @DisplayName("checkIn：leaseId/sessionId/workMode/maxConcurrent/expiresAt 同步返回（A0-6 子任务1）")
    void shouldReturnLeaseInfoOnCheckIn() {
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(30);
        AgentDutyLease lease = lease(11L, AgentDutyLeaseStatus.ACTIVE, expiresAt, "uuid-abc");
        when(agentDutyLeaseService.startLease(eq(AGENT_ID), eq("AUTO"), eq(3), eq(30))).thenReturn(lease);

        McpToolService.CheckInResult result = mcpToolService.checkIn(AGENT_ID, "AUTO", 3, 30);

        assertThat(result.isOk()).isTrue();
        assertThat(result.getLeaseId()).isEqualTo(11L);
        assertThat(result.getSessionId()).isEqualTo("uuid-abc");
        assertThat(result.getWorkMode()).isEqualTo("AUTO");
        assertThat(result.getMaxConcurrent()).isEqualTo(3);
        assertThat(result.getExpiresAt()).isEqualTo(expiresAt.toString());
    }

    @Test
    @DisplayName("checkOut：正常签退后 currentStatus=CLOSED 且带租约事实（A0-6 子任务2）")
    void shouldReturnClosedStatusOnCheckOut() {
        when(agentDutyLeaseService.closeLease(eq(AGENT_ID), eq("shutdown"))).thenReturn(1);
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(30);
        AgentDutyLease latest = lease(11L, AgentDutyLeaseStatus.CLOSED, expiresAt, "uuid-abc");
        latest.setCloseReason("shutdown");
        when(agentDutyLeaseService.getLatestLease(AGENT_ID)).thenReturn(latest);

        McpToolService.CheckOutResult result = mcpToolService.checkOut(AGENT_ID, "shutdown");

        assertThat(result.isOk()).isTrue();
        assertThat(result.getClosedCount()).isEqualTo(1);
        assertThat(result.getCurrentStatus()).isEqualTo("CLOSED");
        assertThat(result.getLatestLeaseId()).isEqualTo(11L);
        assertThat(result.getLatestLeaseExpiresAt()).isEqualTo(expiresAt.toString());
        assertThat(result.getLatestLeaseCloseReason()).isEqualTo("shutdown");
    }

    @Test
    @DisplayName("checkOut 幂等：租约已过期返回 currentStatus=EXPIRED（A0-6 子任务2）")
    void shouldReturnExpiredStatusWhenLeaseExpired() {
        when(agentDutyLeaseService.closeLease(eq(AGENT_ID), eq("shutdown"))).thenReturn(0);
        AgentDutyLease latest = lease(22L, AgentDutyLeaseStatus.EXPIRED,
                OffsetDateTime.now().minusMinutes(5), "uuid-expired");
        latest.setCloseReason("lease_expired");
        when(agentDutyLeaseService.getLatestLease(AGENT_ID)).thenReturn(latest);

        McpToolService.CheckOutResult result = mcpToolService.checkOut(AGENT_ID, "shutdown");

        assertThat(result.isOk()).isTrue();
        assertThat(result.getClosedCount()).isZero();
        assertThat(result.getCurrentStatus()).isEqualTo("EXPIRED");
        assertThat(result.getLatestLeaseId()).isEqualTo(22L);
        assertThat(result.getLatestLeaseCloseReason()).isEqualTo("lease_expired");
    }

    @Test
    @DisplayName("checkOut 幂等：从未打卡返回 currentStatus=NONE（A0-6 子任务2）")
    void shouldReturnNoneWhenNeverCheckedIn() {
        when(agentDutyLeaseService.closeLease(eq(AGENT_ID), eq("manual_close"))).thenReturn(0);
        when(agentDutyLeaseService.getLatestLease(AGENT_ID)).thenReturn(null);

        McpToolService.CheckOutResult result = mcpToolService.checkOut(AGENT_ID, null);

        assertThat(result.isOk()).isTrue();
        assertThat(result.getClosedCount()).isZero();
        assertThat(result.getCurrentStatus()).isEqualTo("NONE");
        assertThat(result.getLatestLeaseId()).isNull();
        assertThat(result.getLatestLeaseExpiresAt()).isNull();
    }

    @Test
    @DisplayName("heartbeat：持有 ACTIVE 租约时返回 onDuty/leaseId/leaseExpiresAt/remainingTtlSeconds（A0-6 子任务3）")
    void shouldReturnRemainingTtlWhenActiveLease() {
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(10);
        AgentDutyLease active = lease(33L, AgentDutyLeaseStatus.ACTIVE, expiresAt, "uuid-active");
        when(agentDutyLeaseService.getActiveLease(AGENT_ID)).thenReturn(active);

        McpToolService.HeartbeatResult result = mcpToolService.heartbeat(AGENT_ID);

        assertThat(result.isOk()).isTrue();
        assertThat(result.getOnDuty()).isTrue();
        assertThat(result.getLeaseId()).isEqualTo(33L);
        assertThat(result.getLeaseExpiresAt()).isEqualTo(expiresAt.toString());
        // plusMinutes(10) 后剩余 TTL 应落在 (540, 600] 秒区间
        assertThat(result.getRemainingTtlSeconds()).isGreaterThan(540L).isLessThanOrEqualTo(600L);
    }

    @Test
    @DisplayName("heartbeat：无 ACTIVE 租约时 onDuty=false 且 remainingTtlSeconds=0（A0-6 子任务3）")
    void shouldReturnOffDutyWhenNoActiveLease() {
        when(agentDutyLeaseService.getActiveLease(AGENT_ID)).thenReturn(null);

        McpToolService.HeartbeatResult result = mcpToolService.heartbeat(AGENT_ID);

        assertThat(result.isOk()).isTrue();
        assertThat(result.getOnDuty()).isFalse();
        assertThat(result.getLeaseId()).isNull();
        assertThat(result.getLeaseExpiresAt()).isNull();
        assertThat(result.getRemainingTtlSeconds()).isZero();
    }

    @Test
    @DisplayName("A0-8：业务工具调用（pullTasks）按原 TTL 自动续租（90 分钟窗口）")
    void shouldAutoRenewLeaseOnBusinessToolCall() {
        OffsetDateTime start = OffsetDateTime.now().minusMinutes(60);
        OffsetDateTime expire = OffsetDateTime.now().plusMinutes(30);
        AgentDutyLease active = lease(33L, AgentDutyLeaseStatus.ACTIVE, expire, "uuid-active");
        active.setStartTime(start);
        when(agentDutyLeaseService.getActiveLease(AGENT_ID)).thenReturn(active);
        when(agentInboxService.getUnread(AGENT_ID, 10)).thenReturn(List.of());

        mcpToolService.pullTasks(AGENT_ID, "EXECUTOR", 10);

        // 原 TTL = start→expire = 90 分钟，续租窗口沿用（而非固定 30）
        verify(agentDutyLeaseService).renewLease(eq(AGENT_ID), eq(90));
    }

    @Test
    @DisplayName("A0-8：heartbeat 顺带续租，返回续租后剩余 TTL")
    void shouldAutoRenewLeaseOnHeartbeatAndReportPostRenewTtl() {
        OffsetDateTime start = OffsetDateTime.now().minusMinutes(30);
        OffsetDateTime expire = OffsetDateTime.now().plusMinutes(30);
        AgentDutyLease active = lease(33L, AgentDutyLeaseStatus.ACTIVE, expire, "uuid-active");
        active.setStartTime(start);
        when(agentDutyLeaseService.getActiveLease(AGENT_ID)).thenReturn(active);

        McpToolService.HeartbeatResult result = mcpToolService.heartbeat(AGENT_ID);

        verify(agentDutyLeaseService).renewLease(eq(AGENT_ID), eq(60));
        assertThat(result.isOk()).isTrue();
        assertThat(result.getOnDuty()).isTrue();
        // 续租窗口 30 分钟 → 剩余 TTL 应接近 1800s
        assertThat(result.getRemainingTtlSeconds()).isGreaterThan(1700L).isLessThanOrEqualTo(1800L);
    }

    @Test
    @DisplayName("A0-8：无 ACTIVE 租约时工具调用不自动打卡（renewLease 不被调用）")
    void shouldNotRenewLeaseWithoutActiveLease() {
        when(agentDutyLeaseService.getActiveLease(AGENT_ID)).thenReturn(null);
        when(agentInboxService.getUnread(AGENT_ID, 10)).thenReturn(List.of());

        mcpToolService.pullTasks(AGENT_ID, "EXECUTOR", 10);

        verify(agentDutyLeaseService, never()).renewLease(anyLong(), anyInt());
    }

    @Test
    @DisplayName("A0-8：续租异常不阻断工具调用（pullTasks 仍正常返回）")
    void shouldKeepToolResultWhenRenewFails() {
        OffsetDateTime start = OffsetDateTime.now().minusMinutes(60);
        OffsetDateTime expire = OffsetDateTime.now().plusMinutes(30);
        AgentDutyLease active = lease(33L, AgentDutyLeaseStatus.ACTIVE, expire, "uuid-active");
        active.setStartTime(start);
        when(agentDutyLeaseService.getActiveLease(AGENT_ID)).thenReturn(active);
        when(agentDutyLeaseService.renewLease(anyLong(), anyInt())).thenThrow(new RuntimeException("renew failed"));
        when(agentInboxService.getUnread(AGENT_ID, 10)).thenReturn(List.of(subTaskInbox(SUB_TASK_ID)));
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask(AGENT_ID));

        McpToolService.PullTasksResult result = mcpToolService.pullTasks(AGENT_ID, "EXECUTOR", 10);

        assertThat(result.getMessages()).hasSize(1);
        assertThat(result.getMessages().get(0).getMessageId()).isEqualTo("inbox-" + SUB_TASK_ID);
    }
}
