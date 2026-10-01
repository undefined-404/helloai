package com.helloai.core.task.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.port.SubTaskCommandPort;
import com.helloai.core.task.service.SubTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SubTaskCommandPort} 的提供方实现（task 域）。
 *
 * <p>承接原 agent 域 {@code SubTaskExecutionServiceImpl.startIfNeeded} 的<b>判定逻辑</b>
 * （状态机规则归 task 域），并由本域 {@link SubTaskService} 完成实际写入；
 * 事务边界与原实现一致（{@code rollbackFor = Exception.class}，提供方为
 * {@code @Transactional} 传播加入）。实现侧依赖 {@code task → agent.port} 属顺向合法。</p>
 */
@Service
@RequiredArgsConstructor
public class SubTaskCommandPortAdapter implements SubTaskCommandPort {

    private final SubTaskService subTaskService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startIfNeeded(Long subTaskId, SubTaskStatus status) {
        if (status == SubTaskStatus.IN_PROGRESS) {
            return;
        }
        if (status == SubTaskStatus.ASSIGNED || status == SubTaskStatus.REWORK || status == SubTaskStatus.PAUSED) {
            subTaskService.start(subTaskId);
            return;
        }
        throw new BizException("子任务状态不允许执行: subTaskId=" + subTaskId + ", status=" + status);
    }

    @Override
    public void unlinkByAssignedAgent(Long agentId) {
        // 级联删除调用方持有事务，此处不再叠加 @Transactional（与原 SubTaskService 语义一致：
        // SubTaskService.unlinkByAssignedAgent 自身按 REQUIRED 传播）。
        subTaskService.unlinkByAssignedAgent(agentId);
    }
}
