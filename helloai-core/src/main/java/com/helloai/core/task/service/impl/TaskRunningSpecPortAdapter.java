package com.helloai.core.task.service.impl;

import com.helloai.core.agent.port.TaskRunningSpecPort;
import com.helloai.core.task.service.TaskRunningSpecService;
import com.helloai.core.task.spec.ExecutionRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link TaskRunningSpecPort} 的提供方实现（task 域）。
 *
 * <p>纯薄委托：转发到 {@link TaskRunningSpecService#findRecord}，仅把
 * {@code ExecutionRecord} 收敛为消费方实际需要的摘要字段。实现侧依赖
 * {@code task → agent.port} 属顺向合法。</p>
 */
@Service
@RequiredArgsConstructor
public class TaskRunningSpecPortAdapter implements TaskRunningSpecPort {

    private final TaskRunningSpecService taskRunningSpecService;

    @Override
    public String findExecutionSummary(Long taskId, Long subTaskId) {
        ExecutionRecord record = taskRunningSpecService.findRecord(taskId, subTaskId);
        return record != null ? record.summary() : null;
    }
}
