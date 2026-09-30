package com.helloai.core.agent.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.service.SubTaskExecutionService;
import com.helloai.core.task.service.SubTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 子任务执行服务（G-002 单轨，2026-09-30）。
 *
 * <p>旧链执行编排（executeCommand / executeOnce / Prompt 装配 / Timeline / 会话 / 对话流）
 * 已整体退役：装配职责迁移至 {@code AgentRuntimeContextAssembler}，编排职责收敛到
 * {@code LocalExecutionCommandConsumer}。本类仅保留状态推进入口 {@link #startIfNeeded}——
 * 消费侧在 Runtime 真身执行前的幂等状态前置（ASSIGNED / REWORK / PAUSED → IN_PROGRESS）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubTaskExecutionServiceImpl implements SubTaskExecutionService {

    private final SubTaskService subTaskService;

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
}