package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.task.CreateTaskRequest;
import com.helloai.api.dto.task.UpdateTaskStatusRequest;
import com.helloai.common.base.R;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.entity.Task;
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

    @PostMapping
    public R<Task> create(@Valid @RequestBody CreateTaskRequest req) {
        Task task = new Task();
        task.setTitle(req.getTitle());
        task.setDescription(req.getDescription());
        task.setStatus(TaskStatus.PENDING);
        taskService.save(task);
        log.info("任务创建: id={}, title={}", task.getId(), req.getTitle());
        return R.ok(task);
    }

    @GetMapping
    public R<?> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status) {
        var wrapper = new LambdaQueryWrapper<Task>()
                .eq(status != null && !status.isBlank(), Task::getStatus, TaskStatus.valueOf(status))
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
    public R<Task> getById(@PathVariable Long id) {
        Task task = taskService.getById(id);
        if (task == null) return R.fail("任务不存在");
        return R.ok(task);
    }

    @PutMapping("/{id}/status")
    public R<Task> updateStatus(@PathVariable Long id,
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
    public R<Task> update(@PathVariable Long id, @RequestBody CreateTaskRequest req) {
        Task task = taskService.getById(id);
        if (task == null) return R.fail("任务不存在");
        task.setTitle(req.getTitle());
        task.setDescription(req.getDescription());
        taskService.updateById(task);
        return R.ok(task);
    }
}
