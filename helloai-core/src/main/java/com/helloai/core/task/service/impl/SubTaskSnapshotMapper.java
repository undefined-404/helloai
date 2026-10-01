package com.helloai.core.task.service.impl;

import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.task.entity.SubTask;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code task.entity.SubTask} → {@code agent.port.SubTaskSnapshot} 的映射（task 域侧）。
 *
 * <p><b>为什么由提供方映射</b>：映射方向是 {@code task → agent}，属 CODE_STYLE §6 顺向合法；
 * 由消费方映射则会重新引入 {@code agent → task} 反向依赖。快照类本身位于
 * {@code agent.port}，<b>不得</b> import task 实体。</p>
 *
 * <p><b>为什么集中一处</b>：事件发布（{@code SubTaskServiceImpl}）与只读查询
 * （{@code SubTaskQueryPortAdapter}）都要产出该快照，集中可避免两份映射口径漂移。</p>
 */
final class SubTaskSnapshotMapper {

    private SubTaskSnapshotMapper() {
    }

    /**
     * 单条映射；入参为 {@code null} 时返回 {@code null}（保持调用方原空值语义）。
     */
    static SubTaskSnapshot toSnapshot(SubTask subTask) {
        if (subTask == null) {
            return null;
        }
        return new SubTaskSnapshot(subTask.getId(), subTask.getStatus(), subTask.getTaskId(),
                subTask.getAssignedAgentId(), subTask.getContext());
    }

    /**
     * 批量映射；入参为 {@code null} / 空时返回空列表（绝不返回 {@code null}），
     * 逐条 {@code null} 元素跳过。
     */
    static List<SubTaskSnapshot> toSnapshots(List<SubTask> subTasks) {
        if (subTasks == null || subTasks.isEmpty()) {
            return List.of();
        }
        List<SubTaskSnapshot> snapshots = new ArrayList<>(subTasks.size());
        for (SubTask subTask : subTasks) {
            SubTaskSnapshot snapshot = toSnapshot(subTask);
            if (snapshot != null) {
                snapshots.add(snapshot);
            }
        }
        return snapshots;
    }
}
