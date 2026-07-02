package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.subtask.CreateSubTaskRequest;
import com.helloai.api.dto.subtask.ReassignRequest;
import com.helloai.api.dto.subtask.ReworkRequest;
import com.helloai.api.dto.subtask.SubTaskResponse;
import com.helloai.common.base.R;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.entity.SubTask;
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
        return R.ok(toResponse(subTask));
    }

    @GetMapping
    public R<List<SubTaskResponse>> list(
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long assignedAgent) {
        var wrapper = new LambdaQueryWrapper<SubTask>()
                .eq(taskId != null, SubTask::getTaskId, taskId)
                .eq(status != null && !status.isBlank(), SubTask::getStatus, SubTaskStatus.valueOf(status))
                .eq(assignedAgent != null, SubTask::getAssignedAgent, assignedAgent)
                .orderByDesc(SubTask::getCreateTime);
        List<SubTaskResponse> list = subTaskService.list(wrapper).stream().map(this::toResponse).toList();
        return R.ok(list);
    }

    @GetMapping("/{id}")
    public R<SubTaskResponse> getById(@PathVariable Long id) {
        SubTask subTask = subTaskService.getById(id);
        if (subTask == null) return R.fail("子任务不存在");
        return R.ok(toResponse(subTask));
    }

    /**
     * 通用状态变更（前端兼容）
     */
    @PostMapping("/change-status")
    public R<Void> changeStatus(@RequestBody Map<String, Object> body) {
        Long subTaskId = Long.valueOf(body.get("subTaskId").toString());
        String newStatus = (String) body.get("newStatus");
        // 委托给对应的 action 方法
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
    public R<Void> claim(@PathVariable Long id, @RequestParam Long agentId) {
        subTaskService.claim(id, agentId);
        return R.ok();
    }

    @PostMapping("/{id}/start")
    public R<Void> start(@PathVariable Long id) {
        subTaskService.start(id);
        return R.ok();
    }

    @PostMapping("/{id}/submit")
    public R<Void> submit(@PathVariable Long id) {
        subTaskService.submit(id);
        return R.ok();
    }

    @PostMapping("/{id}/complete")
    public R<Void> complete(@PathVariable Long id) {
        subTaskService.complete(id);
        return R.ok();
    }

    @PostMapping("/{id}/rework")
    public R<Void> rework(@PathVariable Long id, @RequestBody ReworkRequest req) {
        subTaskService.rework(id, req.getReworkAgentId());
        return R.ok();
    }

    @PostMapping("/{id}/block")
    public R<Void> block(@PathVariable Long id) {
        subTaskService.block(id);
        return R.ok();
    }

    @PostMapping("/{id}/reassign")
    public R<Void> reassign(@PathVariable Long id, @Valid @RequestBody ReassignRequest req) {
        subTaskService.reassign(id, req.getAgentId());
        return R.ok();
    }

    /**
     * 获取可认领的子任务列表
     */
    @GetMapping("/available")
    public R<List<SubTaskResponse>> available() {
        List<SubTask> list = subTaskService.list(
                new LambdaQueryWrapper<SubTask>()
                        .eq(SubTask::getStatus, SubTaskStatus.PENDING)
                        .orderByDesc(SubTask::getCreateTime));
        return R.ok(list.stream().map(this::toResponse).toList());
    }

    @GetMapping("/mine")
    public R<List<SubTaskResponse>> mine(@RequestParam Long agentId) {
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
