package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.api.dto.module.CreateModuleRequest;
import com.helloai.api.dto.module.ModuleResponse;
import com.helloai.common.base.R;
import com.helloai.core.system.entity.Module;
import com.helloai.core.system.service.ModuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/tasks/{taskId}/modules")
@RequiredArgsConstructor
public class ModuleController {

    private final ModuleService moduleService;

    @GetMapping
    public R<List<ModuleResponse>> list(@PathVariable("taskId") Long taskId) {
        List<Module> modules = moduleService.list(
                new LambdaQueryWrapper<Module>()
                        .eq(Module::getTaskId, taskId)
                        .orderByAsc(Module::getSortOrder));
        List<ModuleResponse> resp = modules.stream().map(this::toResponse).toList();
        return R.ok(resp);
    }

    @PostMapping
    public R<ModuleResponse> create(@PathVariable("taskId") Long taskId,
                                    @Valid @RequestBody CreateModuleRequest req) {
        // 检查任务存在
        if (moduleService.getTaskById(taskId) == null) {
            return R.fail("任务不存在: " + taskId);
        }
        Module module = new Module();
        module.setTaskId(taskId);
        module.setName(req.getName());
        module.setSortOrder(0);
        moduleService.save(module);
        log.info("模块创建: taskId={}, moduleId={}, name={}", taskId, module.getId(), req.getName());
        return R.ok(toResponse(module));
    }

    private ModuleResponse toResponse(Module module) {
        ModuleResponse response = new ModuleResponse();
        response.setId(module.getId());
        response.setTaskId(module.getTaskId());
        response.setName(module.getName());
        response.setSortOrder(module.getSortOrder());
        response.setCreateTime(module.getCreateTime());
        return response;
    }
}
