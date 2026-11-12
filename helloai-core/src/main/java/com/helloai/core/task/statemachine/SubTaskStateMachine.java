package com.helloai.core.task.statemachine;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.SubTaskStatus;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

public class SubTaskStateMachine {

    private static final Map<SubTaskStatus, Set<SubTaskStatus>> TRANSITIONS = new EnumMap<>(SubTaskStatus.class);

    static {
        TRANSITIONS.put(SubTaskStatus.PENDING,     Set.of(SubTaskStatus.ASSIGNED, SubTaskStatus.CANCELLED, SubTaskStatus.DEAD_LETTER));
        TRANSITIONS.put(SubTaskStatus.ASSIGNED,     Set.of(SubTaskStatus.IN_PROGRESS, SubTaskStatus.BLOCKED, SubTaskStatus.PENDING, SubTaskStatus.CANCELLED, SubTaskStatus.DEAD_LETTER));
        // PENDING 仅限租约过期回收路径（LeaseReconcilerTask）：Worker 崩溃后
        // 租约到期将任务退回分发链重派；人工/正常路径不允许把在执行的子任务打回 PENDING。
        TRANSITIONS.put(SubTaskStatus.IN_PROGRESS,  Set.of(SubTaskStatus.PENDING, SubTaskStatus.REVIEW, SubTaskStatus.BLOCKED, SubTaskStatus.PAUSED, SubTaskStatus.CANCELLED, SubTaskStatus.DEAD_LETTER));
        // PAUSED → RESTRUCTURING：暂停中的任务被人工判定「需要重构」时，转人工接管派生。
        TRANSITIONS.put(SubTaskStatus.PAUSED,       Set.of(SubTaskStatus.IN_PROGRESS, SubTaskStatus.CANCELLED, SubTaskStatus.RESTRUCTURING));
        // REVIEW → DEAD_LETTER 供核验返工熔断（返工达上限自动核验停止）入死信，
        // 与调度维度重分配熔断对称：自动链路停止，转人工兜底，前端可按死信筛选。
        TRANSITIONS.put(SubTaskStatus.REVIEW,       Set.of(SubTaskStatus.DONE, SubTaskStatus.REWORK, SubTaskStatus.CANCELLED, SubTaskStatus.DEAD_LETTER));
        // REWORK → BLOCKED：返工期同样可能出现外部依赖不可用/环境缺失等阻塞，
        // 需允许 reportBlocked 上报（否则被驳回的任务在返工途中既提交不了也报不了阻塞，
        // 外部 Agent 只能空转等待人工放行）。
        // REWORK → RESTRUCTURING：返工反复失败时不再重试，转人工重构（派生独立 Task）。
        TRANSITIONS.put(SubTaskStatus.REWORK,       Set.of(SubTaskStatus.IN_PROGRESS, SubTaskStatus.BLOCKED, SubTaskStatus.RESTRUCTURING, SubTaskStatus.CANCELLED, SubTaskStatus.DEAD_LETTER));
        // BLOCKED → RESTRUCTURING：外部依赖长期不可用/需求本身有误时，人工重构替代无限阻塞。
        TRANSITIONS.put(SubTaskStatus.BLOCKED,      Set.of(SubTaskStatus.PENDING, SubTaskStatus.RESTRUCTURING, SubTaskStatus.CANCELLED, SubTaskStatus.DEAD_LETTER));
        TRANSITIONS.put(SubTaskStatus.DONE,         Set.of());
        TRANSITIONS.put(SubTaskStatus.CANCELLED,    Set.of());
        // 死信态：仅允许人工处置——指派（ASSIGNED）/放弃（CANCELLED）/直接验收（DONE）/驳回改派（REWORK），
        // 自动重派/兜底定时任务只扫 PENDING/ASSIGNED 等状态，不会碰死信；
        // DONE/REWORK 供人工介入面板（reviewApi 直接通过/驳回改派）从死信池打捞。
        TRANSITIONS.put(SubTaskStatus.DEAD_LETTER,  Set.of(SubTaskStatus.ASSIGNED, SubTaskStatus.CANCELLED, SubTaskStatus.DONE, SubTaskStatus.REWORK, SubTaskStatus.RESTRUCTURING));
        // AWAITING_DERIVED（ADR-008）：唯一来源是派生（草案物化/执行期派生），出边只有三条——
        // 派生 Task 完成（DONE）、派生失败/废除（RESTRUCTURING 人工接管）、人工放弃（CANCELLED）。
        // 不可回 PENDING：本任务的工作已整体移交派生 Task，退回分发链会重复执行。
        TRANSITIONS.put(SubTaskStatus.AWAITING_DERIVED, Set.of(SubTaskStatus.DONE, SubTaskStatus.RESTRUCTURING, SubTaskStatus.CANCELLED));
        // RESTRUCTURING（ADR-008）：人工接管态。可重新派生（AWAITING_DERIVED）、退回分发链重派（PENDING，语义=放弃重构）、放弃（CANCELLED）。
        TRANSITIONS.put(SubTaskStatus.RESTRUCTURING, Set.of(SubTaskStatus.PENDING, SubTaskStatus.AWAITING_DERIVED, SubTaskStatus.CANCELLED));
    }

    public static boolean canTransition(SubTaskStatus from, SubTaskStatus to) {
        Set<SubTaskStatus> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    public static void validate(SubTaskStatus from, SubTaskStatus to) {
        if (!canTransition(from, to)) {
            throw new BizException(String.format("非法状态转换: %s -> %s", from, to));
        }
    }
}
