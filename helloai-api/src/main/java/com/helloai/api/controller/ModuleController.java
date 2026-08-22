package com.helloai.api.controller;

import com.helloai.api.dto.module.CreateModuleRequest;
import com.helloai.api.dto.module.ModuleResponse;
import com.helloai.common.base.R;
import com.helloai.core.task.entity.Module;
import com.helloai.core.task.service.ModuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/modules")
@RequiredArgsConstructor
public class ModuleController {

    private final ModuleService moduleService;

    @GetMapping("/findModulesByTaskId/{taskId}")
    public R<List<ModuleResponse>> findModulesByTaskId(@PathVariable("taskId") Long taskId) {
        List<Module> modules = moduleService.listByTaskId(taskId);
        List<ModuleResponse> resp = modules.stream().map(this::toResponse).toList();
        return R.ok(resp);
    }

    @PostMapping("/setModulesByTaskId/{taskId}")
    public R<ModuleResponse> setModulesByTaskId(@PathVariable("taskId") Long taskId,
                                    @Valid @RequestBody CreateModuleRequest req) {
        Module module = moduleService.create(taskId, req.getName());
        if (module == null) {
            return R.fail("任务不存在: " + taskId);
        }
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
