package com.helloai.core.task.port;

import com.helloai.common.constant.SubTaskStatus;

import java.time.OffsetDateTime;

import java.util.List;
import java.util.Map;

/**
 * 子任务只读快照（task 域对外契约，RM5 批 4）。
 *
 * <p><b>归属判据（CODE_STYLE §7.2 情形②）</b>：消费方 review 域（下标 1）【高于】提供方
 * task 域（下标 2），故读契约落【提供方】{@code task.port}；消费方 {@code review → task.port}
 * 属顺向合法，从此不再 import {@code task.entity.SubTask}。</p>
 *
 * <p><b>字段只纳入消费方实际读取项</b>（review 域逐站点实测并集）：
 * {@code id} / {@code taskId} / {@code status} / {@code assignedAgentId} / {@code reworkCount} /
 * {@code title} / {@code content} / {@code deliverable} / {@code acceptance} / {@code constraints} /
 * {@code context} / {@code uncertainties}。</p>
 *
 * <p><b>不含 {@code requiredSkills}</b>：该字段仅为提供方技能并集计算所需（{@code mergeSkills}），
 * review 不直接读——按 W6 由 {@code SubTaskService#mergeSkills(Long)} 提供方自读，避免为单一
 * 内部用途膨胀快照面。</p>
 *
 * <p><b>写侧不入本契约</b>：review 需要改 {@code context} 时走不透明命令
 * {@code SubTaskService#updateContext(Long, Map)}（W10），契约不携带 {@code version} 等
 * 乐观锁字段。</p>
 */
public record SubTaskView(
        Long id,
        Long taskId,
        SubTaskStatus status,
        Long assignedAgentId,
        Integer reworkCount,
        String title,
        String content,
        String deliverable,
        String acceptance,
        String constraints,
        Map<String, Object> context,
        List<UncertaintyView> uncertainties,
        List<Long> dependsOn,
        OffsetDateTime deadline,
        String priority,
        Integer isContract,
        List<String> requiredSkills,
        Long moduleId,
        Map<String, Object> scoreFactors,
        Integer compositeScore,
        String scoreGrade,
        Integer timeoutCount,
        OffsetDateTime createTime,
        OffsetDateTime updateTime
) {

    /** 兼容构造：不含 planner 侧扩展字段（review 场景）。 */
    public SubTaskView(Long id, Long taskId, SubTaskStatus status, Long assignedAgentId, Integer reworkCount,
                       String title, String content, String deliverable, String acceptance, String constraints,
                       Map<String, Object> context, List<UncertaintyView> uncertainties) {
        this(id, taskId, status, assignedAgentId, reworkCount, title, content, deliverable, acceptance,
                constraints, context, uncertainties, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 仅携带 {@code taskId} 的探针视图（任务级选择场景）。
     *
     * <p>用于「无子任务实体、但要触发任务级策略（如 {@code reviewerAgentId}）」的调用点
     * （原实现构造瞬态 {@code SubTask} 实体再塞 taskId）。集中在此避免逐处拼 12 个位置参数
     * 的错位风险；{@code id} 等其他字段为 {@code null}，消费方须按「仅有 taskId 可用」对待。</p>
     */
    public static SubTaskView taskScopedProbe(Long taskId) {
        return new SubTaskView(null, taskId, null, null, null, null, null, null, null, null, null, List.of());
    }
}
