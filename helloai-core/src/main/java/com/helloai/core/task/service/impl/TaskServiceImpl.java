package com.helloai.core.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.FinalReportStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskPriority;
import com.helloai.common.constant.TaskStatus;
import com.helloai.common.constant.TeamStatus;
import com.helloai.core.agent.port.TeamMemberView;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.TeamService;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.entity.Module;
import com.helloai.core.task.mapper.AttachmentMapper;
import com.helloai.core.task.mapper.ModuleMapper;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.port.TaskDraft;
import com.helloai.core.task.entity.TaskTimeline;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.mapper.TaskAgentMemberMapper;
import com.helloai.core.task.mapper.TaskExecutionRecordMapper;
import com.helloai.core.task.mapper.TaskIterationMapper;
import com.helloai.core.task.mapper.TaskMapper;
import com.helloai.core.task.mapper.TaskRunningSpecMapper;
import com.helloai.core.task.mapper.TaskTimelineMapper;
import com.helloai.core.task.workflow.mapper.WorkflowInstanceMapper;
import com.helloai.core.task.policy.TaskAgentPolicy;
import com.helloai.core.task.policy.TeamPolicyExpander;
import com.helloai.core.task.port.ReviewPort;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.statemachine.FinalReportStateMachine;
import com.helloai.core.task.support.AttachmentObjectPurgeSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务核心服务实现。负责任务级联删除、关联统计、重新发布。
 * 为避免循环依赖，本 Service 直接注入 Mapper 而非依赖其他 Service
 * （AgentInboxService 为无回向依赖的叶子服务，注入以复用门铃链路；
 * AgentService 经 §6.140 收口承接 agent 域数据访问，不再直捅 agent.mapper）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskServiceImpl extends ServiceImpl<TaskMapper, Task> implements TaskService {

    private final SubTaskMapper subTaskMapper;
    private final ModuleMapper moduleMapper;
    private final TaskRunningSpecMapper taskRunningSpecMapper;
    private final TaskExecutionRecordMapper taskExecutionRecordMapper;
    private final TaskAgentMemberMapper taskAgentMemberMapper;
    // D-1（2026-10-05）：task_id 语义列但无外键/原级联漏删的 task 域子表
    private final TaskIterationMapper taskIterationMapper;
    private final WorkflowInstanceMapper workflowInstanceMapper;
    private final ReviewPort reviewPort;
    private final TaskTimelineMapper taskTimelineMapper;
    private final AttachmentMapper attachmentMapper;
    /** 对象存储回收（P3-3，2026-10-07）：级联删除提交后 best-effort 删对象；独立无环承载点，见其类注释。 */
    private final AttachmentObjectPurgeSupport attachmentObjectPurgeSupport;
    private final AgentInboxService agentInboxService;
    private final AgentService agentService;
    private final SubTaskService subTaskService;
    private final TeamService teamService;

    // ══════════════════════════════════════════════════════════════
    //  基础 CRUD（§6.3 收口：条件构造与写操作归 Service）
    // ══════════════════════════════════════════════════════════════

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Task createTask(String title, String description) {
        return createTask(title, description, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Task createTask(String title, String description, Integer slaMinutes) {
        return createTask(title, description, slaMinutes, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Task createTask(String title, String description, Integer slaMinutes,
                           Map<String, Object> agentPolicy, List<String> requiredSkills) {
        Task task = new Task();
        task.setTitle(title);
        task.setDescription(description);
        task.setSlaMinutes(slaMinutes);
        task.setAgentPolicy(expandAgentPolicy(agentPolicy));
        task.setRequiredSkills(requiredSkills);
        task.setPriority(TaskPriority.DEFAULT().name());
        task.setStatus(TaskStatus.PENDING);
        save(task);
        log.info("任务创建: id={}, title={}, slaMinutes={}, agentPolicy={}, requiredSkills={}",
                task.getId(), title, slaMinutes, agentPolicy, requiredSkills);
        return task;
    }

    /**
     * 任务创建时展开 {@code agent_policy.teamId} 槽位快照（N-002，C2-S2）。
     *
     * <p>无 teamId 零开销直通；有 teamId 校验 Team 已 ACTIVE 后按成员展开
     * executor/planner/reviewer 槽位（显式指定槽位保留），展开结果落库，
     * 后续派发不受 Team 成员变更影响（快照隔离，C2 决策 2）。</p>
     */
    private Map<String, Object> expandAgentPolicy(Map<String, Object> agentPolicy) {
        Long teamId = TaskAgentPolicy.teamId(agentPolicy);
        if (teamId == null) {
            return agentPolicy;
        }
        if (teamService.getTeamStatus(teamId) != TeamStatus.ACTIVE) {
            throw new BizException("Team 未发布（非 ACTIVE），不可作为 agent_policy 展开源: teamId=" + teamId);
        }
        List<TeamMemberView> members = teamService.listMemberViews(teamId);
        if (members.isEmpty()) {
            throw new BizException("Team 无成员，无法展开 agent_policy: teamId=" + teamId);
        }
        return TeamPolicyExpander.expand(agentPolicy, members);
    }

    @Override
    public IPage<Task> pageTasks(TaskStatus status, Integer page, int pageSize) {
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<Task>()
                .eq(status != null, Task::getStatus, status)
                .orderByDesc(Task::getCreateTime);
        if (page == null || page <= 0) {
            List<Task> all = list(wrapper);
            Page<Task> full = new Page<>(1, Math.max(all.size(), 1));
            full.setRecords(all);
            full.setTotal(all.size());
            return full;
        }
        return page(new Page<>(page, pageSize), wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Task updateStatus(Long id, TaskStatus status) {
        Task task = getById(id);
        if (task == null) {
            return null;
        }
        task.setStatus(status);
        updateById(task);
        // 停止任务：任务置 CANCELLED 时级联取消全部未终态子任务（含草案），
        // 防止“任务已取消但子任务仍被自动派单/继续流转”的割裂；DONE/CANCELLED 跳过。
        if (status == TaskStatus.CANCELLED) {
            int cancelled = 0;
            List<SubTask> subs = subTaskService.lambdaQuery()
                    .eq(SubTask::getTaskId, id)
                    .list();
            for (SubTask st : subs) {
                SubTaskStatus s = st.getStatus();
                if (s != SubTaskStatus.DONE && s != SubTaskStatus.CANCELLED) {
                    subTaskService.changeStatus(st.getId(), SubTaskStatus.CANCELLED, null,
                            Map.of("cancelledByTask", "task_cancelled"));
                    cancelled++;
                }
            }
            TaskTimeline tl = new TaskTimeline();
            tl.setTaskId(id);
            tl.setEventType("task_cancelled");
            tl.setRole(AgentRole.SYSTEM);
            tl.setPayload(Map.of("cancelledSubTaskCount", cancelled));
            taskTimelineMapper.insert(tl);
        }
        log.info("任务状态变更: id={}, status={}", id, status);
        return task;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Task updatePriority(Long id, String priority) {
        Task task = getById(id);
        if (task == null) {
            return null;
        }
        task.setPriority(TaskPriority.normalize(priority));
        updateById(task);
        log.info("任务优先级更新: id={}, priority={}", id, task.getPriority());
        return task;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Task updateTask(Long id, String title, String description) {
        return updateTask(id, title, description, null, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Task updateTask(Long id, String title, String description, Integer slaMinutes,
                           Map<String, Object> agentPolicy, List<String> requiredSkills) {
        Task task = getById(id);
        if (task == null) {
            return null;
        }
        if (title != null) {
            task.setTitle(title);
        }
        if (description != null) {
            task.setDescription(description);
        }
        if (slaMinutes != null) {
            task.setSlaMinutes(slaMinutes);
        }
        if (agentPolicy != null) {
            task.setAgentPolicy(agentPolicy);
        }
        if (requiredSkills != null) {
            task.setRequiredSkills(requiredSkills);
        }
        updateById(task);
        log.info("任务更新: id={}, slaMinutes={}, agentPolicy={}, requiredSkills={}",
                id, slaMinutes, agentPolicy, requiredSkills);
        return task;
    }

    // ══════════════════════════════════════════════════════════════
    //  关联统计（删除前风险提示）
    // ══════════════════════════════════════════════════════════════

    @Override
    public Map<String, Object> getRelatedCounts(Long taskId) {
        Task task = getById(taskId);
        if (task == null) throw new BizException("任务不存在: " + taskId);

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("taskId", taskId);
        counts.put("taskTitle", task.getTitle());
        counts.put("subTaskCount", subTaskMapper.selectCount(
                new LambdaQueryWrapper<SubTask>().eq(SubTask::getTaskId, taskId)).intValue());
        // 正在执行中的子任务：删除后其在途执行结果会被平台丢弃（回查 DB 拿不到子任务）
        counts.put("activeSubTaskCount", subTaskMapper.selectCount(
                new LambdaQueryWrapper<SubTask>().eq(SubTask::getTaskId, taskId)
                        .in(SubTask::getStatus, SubTaskStatus.ASSIGNED, SubTaskStatus.IN_PROGRESS)).intValue());
        counts.put("deadLetterCount", subTaskMapper.selectCount(
                new LambdaQueryWrapper<SubTask>().eq(SubTask::getTaskId, taskId)
                        .eq(SubTask::getStatus, SubTaskStatus.DEAD_LETTER)).intValue());
        counts.put("moduleCount", moduleMapper.selectCount(
                new LambdaQueryWrapper<Module>().eq(Module::getTaskId, taskId)).intValue());
        // reviewPort.countByTaskId 签名返回 long，必须显式 (int) 收口，
        // 否则 Map 装箱为 Long，Controller.toRelatedCounts 的 (Integer) 强转会抛 ClassCastException → related-counts 接口稳定 500。
        counts.put("reviewCount", (int) reviewPort.countByTaskId(taskId));
        counts.put("executionCount", agentService.countExecutionByTaskId(taskId));
        counts.put("unreadInboxCount", agentService.countUnreadInboxByTaskRef(taskId));
        counts.put("timelineCount", taskTimelineMapper.selectCount(
                new LambdaQueryWrapper<TaskTimeline>().eq(TaskTimeline::getTaskId, taskId)).intValue());
        return counts;
    }

    // ══════════════════════════════════════════════════════════════
    //  级联删除
    // ══════════════════════════════════════════════════════════════

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deleteTaskCascade(Long taskId, String confirmTitle) {
        Task task = getById(taskId);
        if (task == null) throw new BizException("任务不存在: " + taskId);
        // O-1（2026-10-05）：确认标题不匹配属调用方入参错误，应收敛为 400。
        // BizException(String) 默认 code=500（GlobalExceptionHandler 据 code 设置 HTTP 状态），
        // 此前误将「标题不匹配」打成 HTTP 500；与 P2-3/P3 口径对齐改为 BizException(400, ...)。
        if (!task.getTitle().equals(confirmTitle)) {
            throw new BizException(400, "任务标题不匹配");
        }

        // 先统计（结果返回给前端展示删除影响面）
        Map<String, Object> counts = getRelatedCounts(taskId);

        // 清理级联数据（物理删除：@TableLogic 会把普通 delete 改写为软删，
        // 这里走 Mapper 自定义 DELETE SQL 真删，不留残留行）。
        // 外键引用 sub_task.id 的 5 张表与 inbox 的 SQL 均依赖 sub_task 子查询，必须先于 sub_task 删除。
        // agent 域痕迹（inbox/execution_record/archive/message）经 AgentService 收口执行，
        // 与任务域删除同一事务，顺序语义保持（inbox 依赖 sub_task/review_record 子查询）。
        int traceCleaned = agentService.physicalDeleteTaskTrace(taskId);
        // §6.146 域迁移：review_record 级联删除经 ReviewPort 收口（review 域同事务执行）
        reviewPort.physicalDeleteByTaskId(taskId);
        // P3-3（2026-10-07）：删行前捞取该任务对象引用（口径与 physicalDeleteByTaskId 一致），
        // 事务提交后同步回收对象存储。此前级联删除只物理删 DB 行、从不触对象存储，
        // 导致 L4 反复 create/delete 时桶内孤儿持续堆积；回收为 best-effort + 事务后置，
        // 失败不回滚、不 500（见 AttachmentObjectPurgeSupport）。
        List<Attachment> taskAttachments = attachmentMapper.selectByTaskId(taskId);
        attachmentMapper.physicalDeleteByTaskId(taskId);
        attachmentObjectPurgeSupport.purgeAfterCommit(taskAttachments);
        taskTimelineMapper.physicalDeleteByTaskId(taskId);
        // P2-4（2026-10-05）：引用 task.id 的子表共 5 张 —— module / sub_task /
        // task_running_spec / task_execution_record / task_agent_member。此前只清前两张，
        // 漏掉承载 Running Spec 与执行记录/成员的三张 → 物理删除 task 行时撞
        // task_running_spec_task_id_fkey 等外键 → HTTP 500。全部先于 task 本体删除。
        taskRunningSpecMapper.physicalDeleteByTaskId(taskId);
        taskExecutionRecordMapper.physicalDeleteByTaskId(taskId);
        taskAgentMemberMapper.physicalDeleteByTaskId(taskId);
        // D-1（2026-10-05）：task 域另两张带 task_id 但无外键的子表，原级联静默漏删
        // （全库孤儿实测：task_iteration 2；workflow_instance 现网 0）。均须先于 task 本体删除。
        taskIterationMapper.deleteByTaskId(taskId);
        workflowInstanceMapper.physicalDeleteByTaskId(taskId);
        subTaskMapper.physicalDeleteByTaskId(taskId);
        moduleMapper.physicalDeleteByTaskId(taskId);
        baseMapper.physicalDeleteById(taskId);

        log.info("任务级联删除完成: id={}, title={}, subTask={}, deadLetter={}, traceCleaned={}",
                taskId, task.getTitle(), counts.get("subTaskCount"),
                counts.get("deadLetterCount"), traceCleaned);
        return counts;
    }

    // ══════════════════════════════════════════════════════════════
    //  重新发布
    // ══════════════════════════════════════════════════════════════

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Task republish(Long taskId) {
        Task task = getById(taskId);
        if (task == null) throw new BizException("任务不存在: " + taskId);
        if (task.getStatus() == TaskStatus.DONE) {
            throw new BizException("已完成的任务不允许重新发布: " + taskId);
        }

        task.setStatus(TaskStatus.PENDING);
        updateById(task);

        List<AgentProfileSnapshot> planners = agentService.listProfilesByRole(AgentRole.PLANNER);
        String eventId = "task.republish." + taskId + "." + System.currentTimeMillis();
        for (AgentProfileSnapshot planner : planners) {
            agentInboxService.send(planner.id(), eventId, "task.republished",
                    "任务重新发布: " + task.getTitle(),
                    task.getDescription() != null ? task.getDescription() : "请查看详情",
                    "task", taskId, "HIGH");
        }
        log.info("任务重新发布: id={}, title={}, 已通知 {} 个 PLANNER", taskId, task.getTitle(), planners.size());
        return task;
    }

    // ══════════════════════════════════════════════════════════════
    //  最终整合报告状态机写口（§12.5）
    // ══════════════════════════════════════════════════════════════

    @Override
    public boolean transitFinalReportStatus(Long taskId, FinalReportStatus from, FinalReportStatus to) {
        // 迁移合法性单点校验：非法迁移是编程错误，快速失败暴露（不静默改状态）
        FinalReportStateMachine.assertTransit(from, to);
        boolean updated = lambdaUpdate()
                .eq(Task::getId, taskId)
                .eq(Task::getFinalReportStatus, from)
                .set(Task::getFinalReportStatus, to)
                .update();
        if (!updated) {
            log.debug("最终报告状态迁移 CAS 未命中（已被其它链路接管）: taskId={}, {} -> {}",
                    taskId, from, to);
        }
        return updated;
    }

    @Override
    public boolean convergeFinalReportToDone(Long taskId, OffsetDateTime reportTime) {
        boolean updated = lambdaUpdate()
                .eq(Task::getId, taskId)
                .eq(Task::getFinalReportStatus, FinalReportStatus.REVIEWING)
                // 陈旧守卫：report_time 变了说明版本已被新生成/回滚接管，旧链不得覆盖新链状态。
                // 注：此处等值由 PG 侧按 timestamptz 语义判定，**天然是瞬间比较**（不由 JVM 侧偏移决定），
                // 与 FinalReportReviewServiceImpl 的 isStale 口径一致；请勿"对称地"改回 Java 侧 equals 比较。
                .eq(Task::getFinalReportTime, reportTime)
                .set(Task::getFinalReportStatus, FinalReportStatus.DONE)
                .update();
        if (!updated) {
            log.debug("最终报告审查收敛放弃（版本已更新或已被收敛）: taskId={}, reportTime={}",
                    taskId, reportTime);
        }
        return updated;
    }

    @Override
    public List<Task> listFinalReportReviewOrphans(int thresholdSeconds, int limit) {
        // 兜底默认与 AgentDispatchProperties.finalReportReviewOrphanThresholdSeconds 同口径（660s），
        // 同样守住「≥ 防双审锁 TTL 600s」不变量，避免非法配置回退到会把在途审查误判孤儿的 300s
        int threshold = thresholdSeconds > 0 ? thresholdSeconds : 660;
        int batch = limit > 0 ? limit : 20;
        OffsetDateTime deadline = OffsetDateTime.now().minusSeconds(threshold);
        List<Task> orphans = baseMapper.selectStaleFinalReportReviewing(deadline, batch);
        return orphans != null ? orphans : List.of();
    }

    @Override
    public List<Task> listTimedOutPlanning(OffsetDateTime deadline, int limit) {
        return baseMapper.selectTimedOutPlanning(deadline, limit);
    }

    // ── 只读快照（RM5 批 4）──

    @Override
    public TaskView getView(Long taskId) {
        Task t = getById(taskId);
        return t == null ? null
                : new TaskView(t.getId(), t.getTitle(), t.getDescription(), t.getFinalReport(),
                        t.getFinalReportStatus(), t.getFinalReportAgentId(), t.getFinalReportTime(),
                        t.getAgentPolicy(), t.getStatus(), t.getSlaMinutes(), t.getPriority(), t.getContext(),
                        t.getRequiredSkills(), t.getCreateTime(), t.getUpdateTime());
    }

    // ── 写命令（RM5 批 5a）──

    @Override
    public boolean casStatus(Long taskId, TaskStatus expect, TaskStatus target) {
        return lambdaUpdate()
                .eq(Task::getId, taskId)
                .eq(Task::getStatus, expect)
                .set(Task::getStatus, target)
                .update();
    }

    @Override
    public TaskView createFromDraft(TaskDraft draft) {
        Task task = new Task();
        task.setTitle(draft.title());
        task.setDescription(draft.description());
        task.setStatus(draft.status());
        task.setContext(draft.context());
        save(task);
        return getView(task.getId());
    }
}
