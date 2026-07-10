package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.task.CreateTaskRequest;
import com.helloai.api.dto.task.UpdateTaskStatusRequest;
import com.helloai.common.base.R;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.entity.Agent;
import com.helloai.core.entity.Task;
import com.helloai.core.service.AgentInboxService;
import com.helloai.core.service.AgentService;
import com.helloai.core.service.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final AgentService agentService;
    private final AgentInboxService agentInboxService;

    @PostMapping
    public R<Task> create(@Valid @RequestBody CreateTaskRequest req) {
        Task task = new Task();
        task.setTitle(req.getTitle());
        task.setDescription(req.getDescription());
        task.setStatus(TaskStatus.PENDING);
        taskService.save(task);
        log.info("任务创建: id={}, title={}", task.getId(), req.getTitle());

        // v1.1 修复: 创建任务后通知所有 PLANNER
        try {
            List<Agent> planners = agentService.listByRole(AgentRole.PLANNER);
            String eventId = "task.create." + task.getId() + "." + System.currentTimeMillis();
            for (Agent planner : planners) {
                agentInboxService.send(planner.getId(), eventId, "task.created",
                        "新任务需要规划: " + req.getTitle(),
                        req.getDescription() != null ? req.getDescription() : "请查看详情",
                        "task", task.getId(), "HIGH");
            }
            log.info("已通知 {} 个 PLANNER Agent", planners.size());
        } catch (Exception e) {
            log.warn("任务创建后通知 PLANNER 失败: taskId={}", task.getId(), e);
        }

        return R.ok(task);
    }

    @GetMapping
    public R<?> list(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(value = "status", required = false) String status) {
        TaskStatus taskStatus = (status != null && !status.isBlank()) ? TaskStatus.valueOf(status) : null;
        var wrapper = new LambdaQueryWrapper<Task>()
                .eq(taskStatus != null, Task::getStatus, taskStatus)
                .orderByDesc(Task::getCreateTime);

        // 前端直接使用列表（不传 page 时返回全部）
        if (page == null || page <= 0) {
            List<Task> list = taskService.list(wrapper);
            return R.ok(list);
        }
        Page<Task> result = taskService.page(new Page<>(page, pageSize), wrapper);
        return R.ok(PageResult.of(result));
    }

    @GetMapping("/{id}")
    public R<Task> getById(@PathVariable("id") Long id) {
        Task task = taskService.getById(id);
        if (task == null) return R.fail("任务不存在");
        return R.ok(task);
    }

    @PutMapping("/status/{id}")
    public R<Task> updateStatus(@PathVariable("id") Long id,
                                 @Valid @RequestBody UpdateTaskStatusRequest req) {
        Task task = taskService.getById(id);
        if (task == null) return R.fail("任务不存在");
        TaskStatus newStatus = TaskStatus.valueOf(req.getStatus());
        task.setStatus(newStatus);
        taskService.updateById(task);
        log.info("任务状态变更: id={}, status={}", id, req.getStatus());
        return R.ok(task);
    }

    @PutMapping("/{id}")
    public R<Task> update(@PathVariable("id") Long id, @RequestBody CreateTaskRequest req) {
        Task task = taskService.getById(id);
        if (task == null) return R.fail("任务不存在");
        task.setTitle(req.getTitle());
        task.setDescription(req.getDescription());
        taskService.updateById(task);
        return R.ok(task);
    }
}
