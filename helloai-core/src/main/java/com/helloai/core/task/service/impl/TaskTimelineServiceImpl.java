package com.helloai.core.task.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.event.AgentEventQueryService;
import com.helloai.core.agent.event.AgentEventTraceItem;
import com.helloai.core.system.notification.NotificationEvent;
import com.helloai.core.system.notification.NotificationMapping;
import com.helloai.core.system.notification.NotificationService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.entity.TaskTimeline;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.mapper.TaskMapper;
import com.helloai.core.task.mapper.TaskTimelineMapper;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 任务事件时间线服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskTimelineServiceImpl extends ServiceImpl<TaskTimelineMapper, TaskTimeline>
        implements TaskTimelineService {

    private final AgentEventQueryService agentEventQueryService;
    private final NotificationService notificationService;
    private final TaskMapper taskMapper;
    private final SubTaskMapper subTaskMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordEvent(Long taskId,
                            Long subTaskId,
                            String eventType,
                            AgentRole role,
                            Long agentId,
                            Map<String, Object> payload) {
        TaskTimeline timeline = new TaskTimeline();
        timeline.setTaskId(taskId);
        timeline.setSubTaskId(subTaskId);
        timeline.setEventType(eventType);
        timeline.setRole(role);
        timeline.setAgentId(agentId);
        timeline.setPayload(payload != null ? payload : Map.of());
        save(timeline);
        log.debug("TaskTimeline event recorded: type={}, agentId={}, role={}", eventType, agentId, role);
        publishHumanNotification(taskId, subTaskId, eventType, timeline.getId(), payload);
    }

    /**
     * 主通道钩子（MSG-3，设计 §3.2/§3.6）：时间线落库后，按事件码白名单决定是否产生人侧消息。
     *
     * <p>业务模块不感知消息存在——新事件只要写时间线，提醒能力自动覆盖。事务语义随写入口：
     * {@code recordEvent}（REQUIRED）与业务同生共死，{@code recordEventInNewTx}（REQUIRES_NEW）
     * 独立事务（失败路径审计），消息天然分两种命运（设计 §3.6）。</p>
     *
     * <p><b>归属解析在 task 域内做</b>：消息域（system）不可反向依赖 task，故 ownerId 由本钩子用
     * {@link TaskMapper} 解析后传入；taskId 缺省时按 subTaskId 经 {@link SubTaskMapper} 兜底
     * （孤儿巡检 catch 块作用域拿不到 taskId，不兜底则归属恒空、消息静默丢失，审查修复 A3）。
     * 事件消息是<b>单一归属人</b>（设计 §3.1）：任务不存在或 owner
     * 为空时<b>跳过</b>，不广播——避免把任务私有的里程碑泄漏给全体用户（广播仅用于公告）。</p>
     *
     * <p><b>白名单前置闸门</b>：仅登记且非 SILENT 的事件码才解析归属，避免高频过程事件
     * （LLM 调用 / 派发跳过等）触发额外的归属查询。归属解析等前置失败只 warn，不阻断时间线业务事实。</p>
     */
    private void publishHumanNotification(Long taskId, Long subTaskId, String eventType,
                                          Long timelineId, Map<String, Object> payload) {
        NotificationMapping.Entry entry = NotificationMapping.resolve(eventType);
        if (entry == null || entry.form() == NotificationMapping.Form.SILENT) {
            return;
        }
        try {
            Long resolvedTaskId = taskId;
            if (resolvedTaskId == null && subTaskId != null) {
                SubTask subTask = subTaskMapper.selectById(subTaskId);
                resolvedTaskId = subTask != null ? subTask.getTaskId() : null;
            }
            Task task = resolvedTaskId == null ? null : taskMapper.selectById(resolvedTaskId);
            Long ownerId = task != null ? task.getOwnerId() : null;
            if (ownerId == null) {
                log.debug("人侧消息跳过：任务归属未知，不广播任务私有事件: eventType={}, taskId={}", eventType, taskId);
                return;
            }
            notificationService.publish(NotificationEvent.builder()
                    .timelineEventCode(eventType)
                    .instanceKey(String.valueOf(timelineId))
                    .sourceType(subTaskId != null ? "SUB_TASK" : "TASK")
                    .sourceId(subTaskId != null ? subTaskId : taskId)
                    .receiverId(ownerId)
                    .payload(payload)
                    .build());
        } catch (Exception e) {
            log.warn("人侧消息钩子前置失败，跳过本条: eventType={}, taskId={}, err={}", eventType, taskId, e.getMessage());
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordEventInNewTx(Long taskId,
                                   Long subTaskId,
                                   String eventType,
                                   AgentRole role,
                                   Long agentId,
                                   Map<String, Object> payload) {
        recordEvent(taskId, subTaskId, eventType, role, agentId, payload);
    }

    @Override
    public List<TaskTimeline> listBySubTaskId(Long subTaskId) {
        if (subTaskId == null) {
            return Collections.emptyList();
        }
        // 粗粒度业务/角色事件（task_timeline，既有载体，ADR-001 §4 不迁移）
        List<TaskTimeline> coarse = lambdaQuery()
                .eq(TaskTimeline::getSubTaskId, subTaskId)
                .orderByAsc(TaskTimeline::getId)
                .list();
        // 细粒度执行轨迹（agent_event，A6 并轨读消费面）
        List<TaskTimeline> trace = agentEventQueryService.traceBySubTaskId(subTaskId).stream()
                .map(this::toTimeline)
                .toList();
        return merge(coarse, trace);
    }

    /**
     * 合并 task_timeline 与 agent_event 时间线，按写入时序升序（createTime ASC，同刻按 id ASC）。
     */
    List<TaskTimeline> merge(List<TaskTimeline> coarse, List<TaskTimeline> trace) {
        return Stream.concat(coarse.stream(), trace.stream())
                .sorted(Comparator
                        .comparing((TaskTimeline t) -> t.getCreateTime() != null ? t.getCreateTime() : OffsetDateTime.MIN)
                        .thenComparing(TaskTimeline::getId, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /** agent_event 轨迹投影 → TaskTimeline 条目（id 用 agent_event 主键，role 缺省 null）。 */
    TaskTimeline toTimeline(AgentEventTraceItem e) {
        TaskTimeline t = new TaskTimeline();
        t.setId(e.getId());
        t.setTaskId(e.getTaskId());
        t.setSubTaskId(e.getSubTaskId());
        t.setEventType(e.getEventType());
        t.setAgentId(e.getAgentId());
        t.setPayload(e.getPayload() != null ? e.getPayload() : Map.of());
        t.setCreateTime(e.getCreateTime());
        return t;
    }
}
