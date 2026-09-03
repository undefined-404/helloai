package com.helloai.core.task.statemachine;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.SubTaskStatus;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

public class SubTaskStateMachine {

    private static final Map<SubTaskStatus, Set<SubTaskStatus>> TRANSITIONS = new EnumMap<>(SubTaskStatus.class);

    static {
        // 规划草案态：确认后转正（PENDING，进入既有分发链）或拒绝（CANCELLED，保留审计）；
        // 草案不可被 claim/assignNext/自动重派触碰（它们只认 PENDING 等状态）。
        TRANSITIONS.put(SubTaskStatus.PENDING_PLAN_REVIEW, Set.of(SubTaskStatus.PENDING, SubTaskStatus.CANCELLED));
        TRANSITIONS.put(SubTaskStatus.PENDING,     Set.of(SubTaskStatus.ASSIGNED, SubTaskStatus.CANCELLED, SubTaskStatus.DEAD_LETTER));
        TRANSITIONS.put(SubTaskStatus.ASSIGNED,     Set.of(SubTaskStatus.IN_PROGRESS, SubTaskStatus.BLOCKED, SubTaskStatus.PENDING, SubTaskStatus.CANCELLED, SubTaskStatus.DEAD_LETTER));
        // PENDING 仅限租约过期回收路径（LeaseReconcilerTask，Phase 0 A2.4）：Worker 崩溃后
        // 租约到期将任务退回分发链重派；人工/正常路径不允许把在执行的子任务打回 PENDING。
        TRANSITIONS.put(SubTaskStatus.IN_PROGRESS,  Set.of(SubTaskStatus.PENDING, SubTaskStatus.REVIEW, SubTaskStatus.BLOCKED, SubTaskStatus.PAUSED, SubTaskStatus.CANCELLED, SubTaskStatus.DEAD_LETTER));
        TRANSITIONS.put(SubTaskStatus.PAUSED,       Set.of(SubTaskStatus.IN_PROGRESS, SubTaskStatus.CANCELLED));
        // REVIEW → DEAD_LETTER 供核验返工熔断（返工达上限自动核验停止）入死信，
        // 与调度维度重分配熔断对称：自动链路停止，转人工兜底，前端可按死信筛选。
        TRANSITIONS.put(SubTaskStatus.REVIEW,       Set.of(SubTaskStatus.DONE, SubTaskStatus.REWORK, SubTaskStatus.CANCELLED, SubTaskStatus.DEAD_LETTER));
        TRANSITIONS.put(SubTaskStatus.REWORK,       Set.of(SubTaskStatus.IN_PROGRESS, SubTaskStatus.CANCELLED, SubTaskStatus.DEAD_LETTER));
        TRANSITIONS.put(SubTaskStatus.BLOCKED,      Set.of(SubTaskStatus.PENDING, SubTaskStatus.CANCELLED, SubTaskStatus.DEAD_LETTER));
        TRANSITIONS.put(SubTaskStatus.DONE,         Set.of());
        TRANSITIONS.put(SubTaskStatus.CANCELLED,    Set.of());
        // 死信态：仅允许人工处置——指派（ASSIGNED）/放弃（CANCELLED）/直接验收（DONE）/驳回改派（REWORK），
        // 自动重派/兜底定时任务只扫 PENDING/ASSIGNED 等状态，不会碰死信；
        // DONE/REWORK 供人工介入面板（reviewApi 直接通过/驳回改派）从死信池打捞。
        TRANSITIONS.put(SubTaskStatus.DEAD_LETTER,  Set.of(SubTaskStatus.ASSIGNED, SubTaskStatus.CANCELLED, SubTaskStatus.DONE, SubTaskStatus.REWORK));
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
