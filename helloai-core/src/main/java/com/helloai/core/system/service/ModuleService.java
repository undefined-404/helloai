package com.helloai.core.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.system.entity.Module;
import com.helloai.core.task.entity.Task;

import java.util.List;

/**
 * 模块服务接口。
 */
public interface ModuleService extends IService<Module> {

    Task getTaskById(Long taskId);

    /**
     * 按主任务查询模块列表（按排序号升序）。
     *
     * <p>按 §6.3 分层红线从 ModuleController 收口。</p>
     */
    List<Module> listByTaskId(Long taskId);

    /**
     * 创建模块；主任务不存在时返回 null（Controller 保持原有 R.fail 语义）。
     *
     * <p>按 §6.3 分层红线从 ModuleController 收口。</p>
     */
    Module create(Long taskId, String name);
}
