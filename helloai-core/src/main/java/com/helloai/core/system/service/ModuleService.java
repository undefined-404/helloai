package com.helloai.core.system.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.core.system.entity.Module;
import com.helloai.core.task.entity.Task;
import com.helloai.core.system.mapper.ModuleMapper;
import com.helloai.core.task.mapper.TaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModuleService extends ServiceImpl<ModuleMapper, Module> {

    private final TaskMapper taskMapper;

    public Task getTaskById(Long taskId) {
        return taskMapper.selectById(taskId);
    }

    /**
     * 按主任务查询模块列表（按排序号升序）。
     *
     * <p>按 §6.3 分层红线从 ModuleController 收口。</p>
     */
    public List<Module> listByTaskId(Long taskId) {
        return lambdaQuery()
                .eq(Module::getTaskId, taskId)
                .orderByAsc(Module::getSortOrder)
                .list();
    }

    /**
     * 创建模块；主任务不存在时返回 null（Controller 保持原有 R.fail 语义）。
     *
     * <p>按 §6.3 分层红线从 ModuleController 收口。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Module create(Long taskId, String name) {
        if (taskMapper.selectById(taskId) == null) {
            return null;
        }
        Module module = new Module();
        module.setTaskId(taskId);
        module.setName(name);
        module.setSortOrder(0);
        save(module);
        log.info("模块创建: taskId={}, moduleId={}, name={}", taskId, module.getId(), name);
        return module;
    }
}
