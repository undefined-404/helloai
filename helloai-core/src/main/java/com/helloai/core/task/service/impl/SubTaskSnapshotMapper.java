package com.helloai.core.task.service.impl;

import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.port.UncertaintySnapshot;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Uncertainty;

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
 *
 * <p><b>映射纪律（2026-10-01 W7）</b>：快照是全量<b>读投影</b>，映射<b>不做业务判定</b>
 * ——唯一例外是 {@code dependsOn} 取 {@link SubTask#dependsOnIdList()} 的 Long 归一化结果
 * （实体自身的既定读取口径，实体 javadoc 明确要求「不要直接遍历 dependsOn 强转 Long」）。
 * {@code uncertainties} 仅逐元素原样投影，<b>不解释、不校验、不归一化 kind</b>。</p>
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
        return SubTaskSnapshot.builder()
                .id(subTask.getId())
                .status(subTask.getStatus())
                .taskId(subTask.getTaskId())
                .assignedAgentId(subTask.getAssignedAgentId())
                .context(subTask.getContext())
                .title(subTask.getTitle())
                .content(subTask.getContent())
                .deliverable(subTask.getDeliverable())
                .acceptance(subTask.getAcceptance())
                .constraints(subTask.getConstraints())
                .priority(subTask.getPriority())
                .isContract(subTask.getIsContract())
                .deadline(subTask.getDeadline())
                .reworkCount(subTask.getReworkCount())
                .attemptTotal(subTask.getAttemptTotal())
                .version(subTask.getVersion())
                .dependsOn(subTask.dependsOnIdList())
                .uncertainties(toUncertaintySnapshots(subTask.getUncertainties()))
                .build();
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

    /**
     * 不确定性申报逐元素投影；{@code null} / 空入参返回空列表（绝不返回 {@code null}），
     * {@code null} 元素跳过。<b>不解释 kind</b> —— 常量语义单源留在 task 域。
     */
    static List<UncertaintySnapshot> toUncertaintySnapshots(List<Uncertainty> uncertainties) {
        if (uncertainties == null || uncertainties.isEmpty()) {
            return List.of();
        }
        List<UncertaintySnapshot> snapshots = new ArrayList<>(uncertainties.size());
        for (Uncertainty uncertainty : uncertainties) {
            if (uncertainty == null) {
                continue;
            }
            snapshots.add(new UncertaintySnapshot(uncertainty.getKind(), uncertainty.getNote()));
        }
        return snapshots;
    }
}
