package com.helloai.core.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.core.entity.Module;
import com.helloai.core.entity.Task;
import com.helloai.core.mapper.ModuleMapper;
import com.helloai.core.mapper.TaskMapper;
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
