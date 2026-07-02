package com.helloai.core.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.entity.SubTask;
import com.helloai.core.mapper.SubTaskMapper;
import com.helloai.core.statemachine.SubTaskStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubTaskService extends ServiceImpl<SubTaskMapper, SubTask> {

    private final AgentOutboxService agentOutboxService;

    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long subTaskId, SubTaskStatus newStatus, Long agentId) {
        SubTask subTask = getById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }

        SubTaskStatus oldStatus = subTask.getStatus();
        SubTaskStateMachine.validate(oldStatus, newStatus);

        subTask.setStatus(newStatus);
        if (agentId != null) {
            subTask.setAssignedAgent(agentId);
        }

        boolean updated = updateById(subTask);
        if (!updated) {
            throw new BizException("并发修改，请重试");
        }

        agentOutboxService.createEvent(subTask, newStatus);

        log.info("任务状态变更: subTaskId={}, from={}, to={}, agentId={}",
                subTaskId, oldStatus, newStatus, agentId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void claim(Long subTaskId, Long agentId) {
        changeStatus(subTaskId, SubTaskStatus.ASSIGNED, agentId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void start(Long subTaskId) {
        changeStatus(subTaskId, SubTaskStatus.IN_PROGRESS, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void submit(Long subTaskId) {
        changeStatus(subTaskId, SubTaskStatus.REVIEW, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void block(Long subTaskId) {
        SubTask subTask = getById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        if (subTask.getStatus() != SubTaskStatus.IN_PROGRESS
                && subTask.getStatus() != SubTaskStatus.ASSIGNED
                && subTask.getStatus() != SubTaskStatus.REWORK) {
            throw new BizException("只能对 IN_PROGRESS/ASSIGNED/REWORK 状态的子任务标记 BLOCKED");
        }
        changeStatus(subTaskId, SubTaskStatus.BLOCKED, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void reassign(Long subTaskId, Long newAgentId) {
        SubTask subTask = getById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        if (subTask.getStatus() != SubTaskStatus.BLOCKED) {
            throw new BizException("只有 BLOCKED 状态才能重新分配");
        }
        subTask.setStatus(SubTaskStatus.PENDING);
        subTask.setAssignedAgent(newAgentId);
        updateById(subTask);

        changeStatus(subTaskId, SubTaskStatus.ASSIGNED, newAgentId);
    }
}
