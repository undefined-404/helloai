package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.api.dto.subtask.CreateSubTaskRequest;
import com.helloai.api.dto.subtask.ReassignRequest;
import com.helloai.api.dto.subtask.ReworkRequest;
import com.helloai.api.dto.subtask.SubTaskResponse;
import com.helloai.common.base.R;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.entity.SubTask;
import com.helloai.core.service.AgentInboxService;
import com.helloai.core.service.SubTaskService;
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
    private final AgentInboxService agentInboxService;

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
        if (req.getAssignedAgent() != null) {
            subTask.setStatus(SubTaskStatus.ASSIGNED);
            subTask.setAssignedAgent(req.getAssignedAgent());
        } else {
            subTask.setStatus(SubTaskStatus.PENDING);
        }
        subTaskService.save(subTask);
        log.info("子任务创建: id={}, title={}, taskId={}", subTask.getId(), req.getTitle(), req.getTaskId());

        // v1.1 修复: 创建时即发送通知，避免 EXECUTOR 轮询不到
        if (req.getAssignedAgent() != null) {
            try {
                String eventId = "subtask.create." + subTask.getId() + "." + System.currentTimeMillis();
                agentInboxService.send(req.getAssignedAgent(), eventId, "sub_task.assigned",
                        "新任务已分配: " + req.getTitle(),
                        "交付物: " + (req.getDeliverable() != null ? req.getDeliverable() : "待确认"),
                        "sub_task", subTask.getId(), "HIGH");
            } catch (Exception e) {
                log.warn("子任务创建后发送通知失败: subtaskId={}", subTask.getId(), e);
            }
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
                .eq(assignedAgent != null, SubTask::getAssignedAgent, assignedAgent)
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

    @PostMapping("/{id}/claim")
    public R<Void> claim(@PathVariable("id") Long id, @RequestParam("agentId") Long agentId) {
        subTaskService.claim(id, agentId);
        return R.ok();
    }

    @PostMapping("/{id}/start")
    public R<Void> start(@PathVariable("id") Long id) {
        subTaskService.start(id);
        return R.ok();
    }

    @PostMapping("/{id}/submit")
    public R<Void> submit(@PathVariable("id") Long id) {
        subTaskService.submit(id);
        return R.ok();
    }

    @PostMapping("/{id}/complete")
    public R<Void> complete(@PathVariable("id") Long id) {
        subTaskService.complete(id);
        return R.ok();
    }

    @PostMapping("/{id}/rework")
    public R<Void> rework(@PathVariable("id") Long id, @RequestBody ReworkRequest req) {
        subTaskService.rework(id, req.getReworkAgentId());
        return R.ok();
    }

    @PostMapping("/{id}/block")
    public R<Void> block(@PathVariable("id") Long id) {
        subTaskService.block(id);
        return R.ok();
    }

    @PostMapping("/{id}/reassign")
    public R<Void> reassign(@PathVariable("id") Long id, @Valid @RequestBody ReassignRequest req) {
        subTaskService.reassign(id, req.getAgentId());
        return R.ok();
    }

    @PostMapping("/{id}/pause")
    public R<Void> pause(@PathVariable("id") Long id) {
        subTaskService.pause(id);
        return R.ok();
    }

    @PostMapping("/{id}/resume")
    public R<Void> resume(@PathVariable("id") Long id) {
        subTaskService.resume(id);
        return R.ok();
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
                .eq(SubTask::getAssignedAgent, agentId)
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
        response.setAssignedAgent(subTask.getAssignedAgent());
        response.setContent(subTask.getContent());
        response.setReworkCount(subTask.getReworkCount());
        response.setDeadline(subTask.getDeadline());
        response.setCompletedAt(subTask.getCompletedAt());
        response.setCreateTime(subTask.getCreateTime());
        response.setUpdateTime(subTask.getUpdateTime());
        return response;
    }
}
