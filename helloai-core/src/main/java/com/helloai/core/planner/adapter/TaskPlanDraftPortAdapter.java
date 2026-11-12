package com.helloai.core.planner.adapter;

import com.helloai.core.planner.mapper.PlanDraftMapper;
import com.helloai.core.task.port.TaskPlanDraftPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** `TaskPlanDraftPort` 的 planner 侧实现：只读暂存区当前轮是否存在。 */
@Component
@RequiredArgsConstructor
public class TaskPlanDraftPortAdapter implements TaskPlanDraftPort {

    private final PlanDraftMapper planDraftMapper;

    @Override
    public boolean hasActiveDrafts(Long taskId) {
        return taskId != null && planDraftMapper.findMaxActiveRound(taskId) > 0;
    }
}
