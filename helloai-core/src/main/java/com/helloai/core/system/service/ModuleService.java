package com.helloai.core.system.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.core.system.entity.Module;
import com.helloai.core.task.entity.Task;
import com.helloai.core.system.mapper.ModuleMapper;
import com.helloai.core.task.mapper.TaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModuleService extends ServiceImpl<ModuleMapper, Module> {

    private final TaskMapper taskMapper;

    public Task getTaskById(Long taskId) {
        return taskMapper.selectById(taskId);
    }
}
