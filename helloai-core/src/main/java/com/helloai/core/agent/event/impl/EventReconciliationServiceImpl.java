package com.helloai.core.agent.event.impl;

import com.helloai.common.constant.AgentEventType;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.event.EventReconciliationService;
import com.helloai.core.agent.mapper.AgentEventMapper;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.SubTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 事件流对账服务实现（Phase 0 B3）。
 *
 * <p>候选源选型：以 {@code sub_task}（业务表）为扫描起点而非事件表——
 * 事件是业务状态的投影，埋点失败/降级时事件缺失正是对账要发现的问题，
 * 若反向以事件流为候选源会漏检全部缺失场景。</p>
 *
 * <p>状态 → 期望事件映射（B2 埋点事实）：
 * <ul>
 *   <li>ASSIGNED → TASK_ASSIGNED（分配/重派/死信兜底重派均发）</li>
 *   <li>IN_PROGRESS → AGENT_STARTED/CONTEXT_BUILT/TOOL_CALL_STARTED/TOOL_CALL_COMPLETED
 *       （executeOnce 内 step 1-4 递增，任一时刻最后事件必属执行链）</li>
 *   <li>REVIEW → AGENT_COMPLETED（提交核验）/REVIEW_STARTED（核验开始）</li>
 *   <li>REWORK → REVIEW_REJECTED（驳回落地）/REWORK_STARTED（rework 入口）</li>
 *   <li>DONE → REVIEW_APPROVED（审核通过）</li>
 * </ul></p>
 *
 * <p>一致性读取：事件与状态变更同事务（B2 埋点均在各 Service 既有事务内），
 * 读到业务状态时事件必已落库，不存在"状态先于事件可见"的竞态。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventReconciliationServiceImpl implements EventReconciliationService {

    /** 对账窗口：只校验最近 N 分钟内有变更的子任务（配合 60s 调度周期覆盖全部活跃任务；超限截断下一轮补）。 */
    private static final int WINDOW_MINUTES = 10;

    /**
     * 业务状态 → 期望的末条事件类型集合（不可变；无 null，Map.of 安全）。
     *
     * <p>不在映射中的状态（PENDING/PENDING_PLAN_REVIEW/PAUSED/BLOCKED/CANCELLED/DEAD_LETTER）
     * 无事件语义，跳过校验（PENDING 为过渡态：回收/失败回退后事件不回退，无法用末条事件表达）。</p>
     */
    private static final Map<SubTaskStatus, Set<String>> EXPECTED_LAST_EVENTS = Map.of(
            SubTaskStatus.ASSIGNED, Set.of(AgentEventType.TASK_ASSIGNED.code()),
            SubTaskStatus.IN_PROGRESS, Set.of(
                    AgentEventType.AGENT_STARTED.code(),
                    AgentEventType.CONTEXT_BUILT.code(),
                    AgentEventType.TOOL_CALL_STARTED.code(),
                    AgentEventType.TOOL_CALL_COMPLETED.code()),
            SubTaskStatus.REVIEW, Set.of(
                    AgentEventType.AGENT_COMPLETED.code(),
                    AgentEventType.REVIEW_STARTED.code()),
            SubTaskStatus.REWORK, Set.of(
                    AgentEventType.REVIEW_REJECTED.code(),
                    AgentEventType.REWORK_STARTED.code()),
            SubTaskStatus.DONE, Set.of(AgentEventType.REVIEW_APPROVED.code()));

    private final SubTaskService subTaskService;
    private final AgentEventMapper agentEventMapper;

    @Override
    public int reconcile(int limit) {
        OffsetDateTime since = OffsetDateTime.now().minusMinutes(WINDOW_MINUTES);
        List<SubTask> candidates = subTaskService.listRecentlyChanged(since, limit);
        int mismatches = 0;
        for (SubTask subTask : candidates) {
            SubTaskStatus status = subTask.getStatus();
            Set<String> expected = EXPECTED_LAST_EVENTS.get(status);
            if (expected == null) {
                // 无事件语义状态：跳过（接口 Javadoc 已列边界，避免对过渡态误报）
                continue;
            }
            String actual = loadLastEventType(subTask.getId());
            if (actual == null || !expected.contains(actual)) {
                mismatches++;
                log.warn("事件对账不一致: subTaskId={}, status={}, expected={}, actual={}",
                        subTask.getId(), status, expected, actual == null ? "<none>" : actual);
            }
        }
        return mismatches;
    }

    /**
     * 读取子任务事件流末条事件类型（Mapper 显式 SQL）。
     *
     * <p>不走 LambdaQueryWrapper 的原因见 {@code AgentEventMapper.selectLastEventTypeBySubTaskId}；
     * 事件缺失（埋点降级/旧路径未埋）时返回 null，由调用方按不一致处理。</p>
     */
    private String loadLastEventType(Long subTaskId) {
        return agentEventMapper.selectLastEventTypeBySubTaskId(subTaskId);
    }
}