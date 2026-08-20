package com.helloai.core.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.helloai.core.system.entity.Module;
import com.helloai.core.system.mapper.AttachmentMapper;
import com.helloai.core.system.mapper.ModuleMapper;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.entity.TaskTimeline;
import com.helloai.core.task.mapper.ReviewRecordMapper;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.mapper.TaskMapper;
import com.helloai.core.task.mapper.TaskTimelineMapper;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务核心服务实现。负责任务级联删除、关联统计、重新发布。
 * 为避免循环依赖，本 Service 直接注入 Mapper 而非依赖其他 Service
 * （AgentInboxService 为无回向依赖的叶子服务，注入以复用门铃链路）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskServiceImpl extends ServiceImpl<TaskMapper, Task> implements TaskService {

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
    private final SubTaskService subTaskService;

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
        task.setAgentPolicy(agentPolicy);
        task.setRequiredSkills(requiredSkills);
        task.setStatus(TaskStatus.PENDING);
        save(task);
        log.info("任务创建: id={}, title={}, slaMinutes={}, agentPolicy={}, requiredSkills={}",
                task.getId(), title, slaMinutes, agentPolicy, requiredSkills);
        return task;
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

    @Override
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
