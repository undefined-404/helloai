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
        TRANSITIONS.put(SubTaskStatus.IN_PROGRESS,  Set.of(SubTaskStatus.REVIEW, SubTaskStatus.BLOCKED, SubTaskStatus.PAUSED, SubTaskStatus.CANCELLED, SubTaskStatus.DEAD_LETTER));
        TRANSITIONS.put(SubTaskStatus.PAUSED,       Set.of(SubTaskStatus.IN_PROGRESS, SubTaskStatus.CANCELLED));
        TRANSITIONS.put(SubTaskStatus.REVIEW,       Set.of(SubTaskStatus.DONE, SubTaskStatus.REWORK, SubTaskStatus.CANCELLED));
        TRANSITIONS.put(SubTaskStatus.REWORK,       Set.of(SubTaskStatus.IN_PROGRESS, SubTaskStatus.CANCELLED, SubTaskStatus.DEAD_LETTER));
        TRANSITIONS.put(SubTaskStatus.BLOCKED,      Set.of(SubTaskStatus.PENDING, SubTaskStatus.CANCELLED, SubTaskStatus.DEAD_LETTER));
        TRANSITIONS.put(SubTaskStatus.DONE,         Set.of());
        TRANSITIONS.put(SubTaskStatus.CANCELLED,    Set.of());
        // V25 死信态：仅允许人工指派（ASSIGNED）或人工放弃（CANCELLED），
        // 自动重派/兜底定时任务只扫 PENDING/ASSIGNED 等状态，不会碰死信。
        TRANSITIONS.put(SubTaskStatus.DEAD_LETTER,  Set.of(SubTaskStatus.ASSIGNED, SubTaskStatus.CANCELLED));
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
