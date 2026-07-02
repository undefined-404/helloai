package com.helloai.api.controller;

import com.helloai.common.base.R;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.api.dto.ChangeStatusRequest;
import com.helloai.api.dto.SubTaskDTO;
import com.helloai.core.entity.SubTask;
import com.helloai.core.service.SubTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/sub-tasks")
@RequiredArgsConstructor
public class SubTaskController {

    private final SubTaskService subTaskService;

    @PostMapping("/change-status")
    public R<Void> changeStatus(@RequestBody ChangeStatusRequest request) {
        SubTaskStatus newStatus = SubTaskStatus.valueOf(request.getNewStatus().toUpperCase());
        subTaskService.changeStatus(request.getSubTaskId(), newStatus, request.getAgentId());
        log.info("状态变更: subTaskId={}, newStatus={}", request.getSubTaskId(), newStatus);
        return R.ok();
    }

    @GetMapping("/{id}")
    public R<SubTaskDTO> getById(@PathVariable Long id) {
        SubTask subTask = subTaskService.getById(id);
        if (subTask == null) {
            return R.fail("子任务不存在");
        }
        return R.ok(toDTO(subTask));
    }

    @GetMapping
    public R<List<SubTaskDTO>> list(@RequestParam(required = false) String status) {
        List<SubTask> list;
        if (status != null && !status.isBlank()) {
            list = subTaskService.lambdaQuery()
                    .eq(SubTask::getStatus, SubTaskStatus.valueOf(status.toUpperCase()))
                    .orderByDesc(SubTask::getCreateTime)
                    .list();
        } else {
            list = subTaskService.lambdaQuery()
                    .orderByDesc(SubTask::getCreateTime)
                    .list();
        }
        return R.ok(list.stream().map(this::toDTO).collect(Collectors.toList()));
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

    @PostMapping("/{id}/block")
    public R<Void> block(@PathVariable Long id) {
        subTaskService.block(id);
        return R.ok();
    }

    @PostMapping("/{id}/reassign")
    public R<Void> reassign(@PathVariable Long id, @RequestParam Long newAgentId) {
        subTaskService.reassign(id, newAgentId);
        return R.ok();
    }

    @GetMapping("/mine")
    public R<List<SubTaskDTO>> mine(@RequestParam Long agentId) {
        List<SubTask> list = subTaskService.lambdaQuery()
                .eq(SubTask::getAssignedAgent, agentId)
                .orderByDesc(SubTask::getCreateTime)
                .list();
        return R.ok(list.stream().map(this::toDTO).collect(Collectors.toList()));
    }

    @GetMapping("/available")
    public R<List<SubTaskDTO>> available() {
        List<SubTask> list = subTaskService.lambdaQuery()
                .eq(SubTask::getStatus, SubTaskStatus.PENDING)
                .orderByDesc(SubTask::getCreateTime)
                .list();
        return R.ok(list.stream().map(this::toDTO).collect(Collectors.toList()));
    }

    private SubTaskDTO toDTO(SubTask subTask) {
        SubTaskDTO dto = new SubTaskDTO();
        dto.setId(subTask.getId());
        dto.setTaskId(subTask.getTaskId());
        dto.setModuleId(subTask.getModuleId());
        dto.setTitle(subTask.getTitle());
        dto.setStatus(subTask.getStatus() != null ? subTask.getStatus().name() : null);
        dto.setAssignedAgent(subTask.getAssignedAgent());
        dto.setContent(subTask.getContent());
        dto.setCompositeScore(subTask.getCompositeScore());
        dto.setScoreGrade(subTask.getScoreGrade());
        dto.setCreateTime(subTask.getCreateTime() != null ? subTask.getCreateTime().toString() : null);
        dto.setUpdateTime(subTask.getUpdateTime() != null ? subTask.getUpdateTime().toString() : null);
        return dto;
    }
}
