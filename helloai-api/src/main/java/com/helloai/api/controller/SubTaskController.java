package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.api.dto.subtask.CreateSubTaskRequest;
import com.helloai.api.dto.subtask.ReassignRequest;
import com.helloai.api.dto.subtask.ReworkRequest;
import com.helloai.api.dto.subtask.SubTaskResponse;
import com.helloai.common.base.R;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.agent.command.ExecutionCommandService;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/sub-tasks")
@RequiredArgsConstructor
public class SubTaskController {

    private final SubTaskService subTaskService;
    private final SubTaskDispatchService subTaskDispatchService;
    private final ExecutionCommandService executionCommandService;
    private final AgentExecutionRecordService agentExecutionRecordService;
    private final HttpServletRequest request;
    private final AgentDispatchProperties agentDispatchProperties;

    @PostMapping
    public R<SubTaskResponse> create(@Valid @RequestBody CreateSubTaskRequest req) {
        SubTask subTask = new SubTask();
        subTask.setTaskId(req.getTaskId());
        subTask.setModuleId(req.getModuleId());
        subTask.setTitle(req.getTitle());
        subTask.setContent(req.getDescription());
        subTask.setDeliverable(req.getDeliverable());
        subTask.setAcceptance(req.getAcceptance());
        subTask.setPriority(req.getPriority() != null ? req.getPriority() : "MEDIUM");
        subTask.setStatus(SubTaskStatus.PENDING);
        subTask = subTaskService.create(subTask, req.getAssignedAgent());
        log.info("子任务创建: id={}, title={}, taskId={}", subTask.getId(), req.getTitle(), req.getTaskId());

        if (req.getAssignedAgent() == null && agentDispatchProperties.isAutoAssignOnCreate()) {
            subTaskDispatchService.dispatchPendingSubTaskAuto(subTask.getId(), AgentRole.EXECUTOR);
            subTask = subTaskService.getById(subTask.getId());
        }

        return R.ok(toResponse(subTask));
    }

    @GetMapping
    public R<List<SubTaskResponse>> list(
            @RequestParam(value = "taskId", required = false) Long taskId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "assignedAgent", required = false) Long assignedAgent) {
        SubTaskStatus statusFilter = (status != null && !status.isBlank()) ? SubTaskStatus.valueOf(status) : null;
        var wrapper = new LambdaQueryWrapper<SubTask>()
                .eq(taskId != null, SubTask::getTaskId, taskId)
                .eq(statusFilter != null, SubTask::getStatus, statusFilter)
                .eq(assignedAgent != null, SubTask::getAssignedAgentId, assignedAgent)
                .orderByDesc(SubTask::getCreateTime);
        List<SubTaskResponse> list = subTaskService.list(wrapper).stream().map(this::toResponse).toList();
        return R.ok(list);
    }

    @GetMapping("/{id}")
    public R<SubTaskResponse> getById(@PathVariable("id") Long id) {
        SubTask subTask = subTaskService.getById(id);
        if (subTask == null) return R.fail("子任务不存在");
        return R.ok(toResponse(subTask));
    }

    @PostMapping("/change-status")
    public R<Void> changeStatus(@RequestBody Map<String, Object> body) {
        Long subTaskId = Long.valueOf(body.get("subTaskId").toString());
        String newStatus = (String) body.get("newStatus");
        switch (SubTaskStatus.valueOf(newStatus)) {
            case ASSIGNED -> {
                Object agentId = body.get("agentId");
                if (agentId != null) {
                    subTaskService.claim(subTaskId, Long.valueOf(agentId.toString()));
                }
            }
            case IN_PROGRESS -> subTaskService.start(subTaskId);
            case REVIEW -> subTaskService.submit(subTaskId);
            case BLOCKED -> subTaskService.block(subTaskId);
            case DONE -> subTaskService.complete(subTaskId);
            case CANCELLED -> subTaskService.cancel(subTaskId);
            default -> log.warn("不支持的 change-status 操作: {}", newStatus);
        }
        return R.ok();
    }

    @PostMapping("/claim/{id}")
    public R<Void> claim(@PathVariable("id") Long id, @RequestParam("agentId") Long agentId) {
        subTaskService.claim(id, agentId);
        return R.ok();
    }

    @PostMapping("/start/{id}")
    public R<Void> start(@PathVariable("id") Long id) {
        subTaskService.start(id);
        return R.ok();
    }

    @PostMapping("/submit/{id}")
    public R<Void> submit(@PathVariable("id") Long id) {
        subTaskService.submit(id);
        return R.ok();
    }

    @PostMapping("/complete/{id}")
    public R<Void> complete(@PathVariable("id") Long id) {
        subTaskService.complete(id);
        return R.ok();
    }

    @PostMapping("/rework/{id}")
    public R<Void> rework(@PathVariable("id") Long id, @RequestBody ReworkRequest req) {
        subTaskService.rework(id, req.getReworkAgentId());
        return R.ok();
    }

    @PostMapping("/block/{id}")
    public R<Void> block(@PathVariable("id") Long id) {
        subTaskService.block(id);
        return R.ok();
    }

    @PostMapping("/reassign/{id}")
    public R<Void> reassign(@PathVariable("id") Long id, @Valid @RequestBody ReassignRequest req) {
        subTaskDispatchService.dispatchBlockedSubTask(id, req.getAgentId());
        return R.ok();
    }

    @PostMapping("/pause/{id}")
    public R<Void> pause(@PathVariable("id") Long id) {
        subTaskService.pause(id);
        return R.ok();
    }

    @PostMapping("/resume/{id}")
    public R<Void> resume(@PathVariable("id") Long id) {
        subTaskService.resume(id);
        return R.ok();
    }

    @PostMapping("/execute/{id}")
    public R<Map<String, Object>> execute(@PathVariable("id") Long id) {
        requireAdmin();

        // 1. subTask 存在
        SubTask subTask = subTaskService.getById(id);
        if (subTask == null) {
            return R.fail("子任务不存在");
        }

        // 2. assignedAgent != null
        if (subTask.getAssignedAgentId() == null) {
            return R.fail("子任务未分配 Agent");
        }

        // 3. status 仅允许 ASSIGNED / REWORK / PAUSED
        SubTaskStatus status = subTask.getStatus();
        if (status != SubTaskStatus.ASSIGNED
                && status != SubTaskStatus.REWORK
                && status != SubTaskStatus.PAUSED) {
            return R.fail("子任务状态不允许执行: " + status);
        }

        // 4. 不存在 PENDING/RUNNING 执行记录，防重复发命令
        if (agentExecutionRecordService.hasPendingOrRunning(id)) {
            return R.fail("子任务已有进行中的执行记录，请勿重复触发");
        }

        ExecutionCommand command = executionCommandService.createAssignedCommand(
                id, subTask.getAssignedAgentId(), "admin-execute");

        return R.ok(Map.of(
                "recordId", command.getRecordId(),
                "eventId", command.getEventId(),
                "subTaskId", command.getSubTaskId(),
                "agentId", command.getAgentId(),
                "trigger", command.getTrigger()));
    }

    private void requireAdmin() {
        Object type = request.getAttribute(com.helloai.api.interceptor.AuthInterceptor.AUTH_TYPE_KEY);
        if (type == null || !"admin".equals(type.toString())) {
            throw new com.helloai.common.base.BizException(403, "admin only");
        }
    }

    @GetMapping("/available")
    public R<List<SubTaskResponse>> available() {
        List<SubTask> list = subTaskService.list(
                new LambdaQueryWrapper<SubTask>()
                        .eq(SubTask::getStatus, SubTaskStatus.PENDING)
                        .orderByDesc(SubTask::getCreateTime));
        return R.ok(list.stream().map(this::toResponse).toList());
    }

    @GetMapping("/mine")
    public R<List<SubTaskResponse>> mine(@RequestParam("agentId") Long agentId) {
        var wrapper = new LambdaQueryWrapper<SubTask>()
                .eq(SubTask::getAssignedAgentId, agentId)
                .orderByDesc(SubTask::getCreateTime);
        List<SubTaskResponse> list = subTaskService.list(wrapper).stream().map(this::toResponse).toList();
        return R.ok(list);
    }

    private SubTaskResponse toResponse(SubTask subTask) {
        SubTaskResponse response = new SubTaskResponse();
        response.setId(subTask.getId());
        response.setTaskId(subTask.getTaskId());
        response.setModuleId(subTask.getModuleId());
        response.setTitle(subTask.getTitle());
        response.setDeliverable(subTask.getDeliverable());
        response.setAcceptance(subTask.getAcceptance());
        response.setPriority(subTask.getPriority());
        response.setStatus(subTask.getStatus());
        response.setAssignedAgent(subTask.getAssignedAgentId());
        response.setContent(subTask.getContent());
        response.setReworkCount(subTask.getReworkCount());
        response.setDeadline(subTask.getDeadline());
        response.setCompletedAt(subTask.getCompleteTime());
        response.setCreateTime(subTask.getCreateTime());
        response.setUpdateTime(subTask.getUpdateTime());
        return response;
    }
}
