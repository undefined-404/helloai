package com.helloai.core.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.mapper.AgentExecutionRecordMapper;
import com.helloai.core.agent.mapper.AgentInboxMapper;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.agent.mapper.ConversationArchiveMapper;
import com.helloai.core.agent.mapper.ConversationMessageMapper;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.system.mapper.AttachmentMapper;
import com.helloai.core.system.mapper.ModuleMapper;
import com.helloai.core.system.entity.Module;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.entity.TaskTimeline;
import com.helloai.core.task.mapper.ReviewRecordMapper;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.mapper.TaskMapper;
import com.helloai.core.task.mapper.TaskTimelineMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务核心服务。负责任务级联删除、关联统计、重新发布。
 * 为避免循环依赖，本 Service 直接注入 Mapper 而非依赖其他 Service
 * （AgentInboxService 为无回向依赖的叶子服务，注入以复用门铃链路）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService extends ServiceImpl<TaskMapper, Task> {

    private final SubTaskMapper subTaskMapper;
    private final ModuleMapper moduleMapper;
    private final ReviewRecordMapper reviewRecordMapper;
    private final AgentExecutionRecordMapper agentExecutionRecordMapper;
    private final AgentInboxMapper agentInboxMapper;
    private final TaskTimelineMapper taskTimelineMapper;
    private final AttachmentMapper attachmentMapper;
    private final ConversationArchiveMapper conversationArchiveMapper;
    private final ConversationMessageMapper conversationMessageMapper;
    private final AgentMapper agentMapper;
    private final AgentInboxService agentInboxService;

    // ══════════════════════════════════════════════════════════════
    //  关联统计（删除前风险提示）
    // ══════════════════════════════════════════════════════════════

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
        counts.put("reviewCount", reviewRecordMapper.countByTaskId(taskId));
        counts.put("executionCount", agentExecutionRecordMapper.countByTaskId(taskId));
        counts.put("unreadInboxCount", agentInboxMapper.countUnreadByTaskRef(taskId));
        counts.put("timelineCount", taskTimelineMapper.selectCount(
                new LambdaQueryWrapper<TaskTimeline>().eq(TaskTimeline::getTaskId, taskId)).intValue());
        return counts;
    }

    // ══════════════════════════════════════════════════════════════
    //  级联删除
    // ══════════════════════════════════════════════════════════════

    /**
     * 任务级联物理删除。
     *
     * <p>单事务内按外键依赖逆序清理，删除后数据库中不再存在该任务的任何行，
     * 与"消息只是门铃、DB 是唯一事实源"的防重原则天然兼容：
     * 旧 Agent 持有的过期通知/在途 MQ 命令在消费时回查 DB 均为 not_found，
     * 会被 claimSubTask / submitResult / LocalExecutionCommandConsumer 各自的
     * 存在性校验直接丢弃，不会产生幽灵执行或残留数据。</p>
     *
     * <p>删除顺序（子查询依赖被引用行仍存在，顺序不可调换）：
     * agent_inbox（含未读“MQ 未查看消息”）→ agent_execution_record → review_record
     * → attachment → conversation_archive → conversation_message
     * → task_timeline → sub_task（含 DEAD_LETTER 死信行）→ module → task。
     * 其中外键引用 sub_task.id 的 5 张表（execution/review/attachment/archive/message）
     * 必须全部先于 sub_task 删除，否则抛 FK 违反。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deleteTaskCascade(Long taskId, String confirmTitle) {
        Task task = getById(taskId);
        if (task == null) throw new BizException("任务不存在: " + taskId);
        if (!task.getTitle().equals(confirmTitle)) {
            throw new BizException("任务标题不匹配，请确认后重试");
        }

        // 先统计（结果返回给前端展示删除影响面）
        Map<String, Object> counts = getRelatedCounts(taskId);

        // 清理级联数据（物理删除：@TableLogic 会把普通 delete 改写为软删，
        // 这里走 Mapper 自定义 DELETE SQL 真删，不留残留行）。
        // 外键引用 sub_task.id 的 5 张表与 inbox 的 SQL 均依赖 sub_task 子查询，必须先于 sub_task 删除。
        int inboxDeleted = agentInboxMapper.physicalDeleteByTaskRef(taskId);
        agentExecutionRecordMapper.physicalDeleteByTaskId(taskId);
        reviewRecordMapper.physicalDeleteByTaskId(taskId);
        attachmentMapper.physicalDeleteByTaskId(taskId);
        conversationArchiveMapper.physicalDeleteByTaskId(taskId);
        conversationMessageMapper.physicalDeleteByTaskId(taskId);
        taskTimelineMapper.physicalDeleteByTaskId(taskId);
        subTaskMapper.physicalDeleteByTaskId(taskId);
        moduleMapper.physicalDeleteByTaskId(taskId);
        baseMapper.physicalDeleteById(taskId);

        log.info("任务级联删除完成: id={}, title={}, subTask={}, deadLetter={}, inboxCleaned={}",
                taskId, task.getTitle(), counts.get("subTaskCount"),
                counts.get("deadLetterCount"), inboxDeleted);
        return counts;
    }

    // ══════════════════════════════════════════════════════════════
    //  重新发布
    // ══════════════════════════════════════════════════════════════

    /**
     * 重新发布任务：状态重置为 PENDING 并重新通知全部 PLANNER。
     *
     * <p>不触碰已有子任务——子任务有独立生命周期与归属校验，重复规划与否
     * 由 PLANNER 侧根据现状决策；使用新 eventId 投递收件箱，
     * (event_id, agent_id) 唯一约束不会与历史通知冲突。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Task republish(Long taskId) {
        Task task = getById(taskId);
        if (task == null) throw new BizException("任务不存在: " + taskId);
        if (task.getStatus() == TaskStatus.DONE) {
            throw new BizException("已完成的任务不允许重新发布: " + taskId);
        }

        task.setStatus(TaskStatus.PENDING);
        updateById(task);

        List<Agent> planners = agentMapper.selectList(
                new LambdaQueryWrapper<Agent>().eq(Agent::getRole, AgentRole.PLANNER));
        String eventId = "task.republish." + taskId + "." + System.currentTimeMillis();
        for (Agent planner : planners) {
            agentInboxService.send(planner.getId(), eventId, "task.republished",
                    "任务重新发布: " + task.getTitle(),
                    task.getDescription() != null ? task.getDescription() : "请查看详情",
                    "task", taskId, "HIGH");
        }
        log.info("任务重新发布: id={}, title={}, 已通知 {} 个 PLANNER", taskId, task.getTitle(), planners.size());
        return task;
    }
}
