package com.helloai.core.service;

import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.entity.SubTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 执行结果处理器。
 *
 * <p>负责把执行成功/失败结果回写到子任务状态机与时间线，
 * 让 {@link SubTaskExecutionService} 更聚焦“执行本身”，
 * 后续也便于把结果处理独立挂接到 MQ/轮询消费端。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionResultHandler {

    private final SubTaskService subTaskService;
    private final TaskTimelineService taskTimelineService;

    @Transactional(rollbackFor = Exception.class)
    public void handleSuccess(Long subTaskId, Long agentId, AgentResult result) {
        SubTask subTask = subTaskService.getById(subTaskId);
        if (subTask == null) {
            return;
        }


        // 防御：如果子任务已被补偿任务推进到 BLOCKED/TIMEOUT，不应继续推进到 REVIEW
        // 场景：补偿任务在 LLM 调用期间将 subTask 标记为 BLOCKED，
        //       consumer 拿到 LLM 结果后不应"复活"子任务。
        if (subTask.getStatus() != SubTaskStatus.IN_PROGRESS) {
            log.warn("跳过 handleSuccess：子任务状态已非 IN_PROGRESS（可能被补偿任务推进）: subTaskId={}, status={}",
                    subTaskId, subTask.getStatus());
            taskTimelineService.recordEvent(
                    subTask.getTaskId(),
                    subTaskId,
                    "sub_task_execute_result_discarded",
                    AgentRole.EXECUTOR,
                    agentId,
                    safeMap(
                            "reason", "subtask_status_not_in_progress",
                            "currentStatus", subTask.getStatus().name(),
                            "llmSuccess", result.isSuccess()));
            return;
        }
        Map<String, Object> ctx = new HashMap<>(subTask.getContext() != null ? subTask.getContext() : Map.of());
        Map<String, Object> last = new HashMap<>();
        last.put("at", OffsetDateTime.now().toString());
        last.put("agentId", agentId);
        last.put("success", result.isSuccess());
        last.put("executor", result.getExecutorName());
        last.put("finishReason", result.getFinishReason());
        last.put("tokens", result.getTokenUsage());
        last.put("output", result.getOutput());
        ctx.put("lastExecution", last);
        subTask.setContext(ctx);
        subTaskService.updateById(subTask);

        subTaskService.submit(subTaskId);
        taskTimelineService.recordEvent(
                subTask.getTaskId(),
                subTaskId,
                "sub_task_execute_submit",
                AgentRole.EXECUTOR,
                agentId,
                safeMap(
                        "success", result.isSuccess(),
                        "executor", result.getExecutorName(),
                        "tokens", result.getTokenUsage()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void handleFailure(Long subTaskId, Long agentId, Exception e) {
        SubTask subTask = subTaskService.getById(subTaskId);
        if (subTask == null) {
            return;
        }

        Map<String, Object> ctx = new HashMap<>(subTask.getContext() != null ? subTask.getContext() : Map.of());
        Map<String, Object> last = new HashMap<>();
        last.put("at", OffsetDateTime.now().toString());
        last.put("agentId", agentId);
        last.put("success", false);
        last.put("error", e.getMessage());
        ctx.put("lastExecution", last);
        subTask.setContext(ctx);
        subTaskService.updateById(subTask);

        if (subTask.getStatus() == SubTaskStatus.IN_PROGRESS) {
            subTaskService.block(subTaskId);
        }
        taskTimelineService.recordEvent(
                subTask.getTaskId(),
                subTaskId,
                "sub_task_execute_failed",
                AgentRole.EXECUTOR,
                agentId,
                safeMap("error", e.getMessage()));
    }

    private static Map<String, Object> safeMap(Object... keyValues) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            if (key instanceof String keyString) {
                result.put(keyString, keyValues[i + 1]);
            }
        }
        return result;
    }
}
