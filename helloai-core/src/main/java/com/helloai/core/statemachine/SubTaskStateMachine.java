package com.helloai.core.statemachine;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.SubTaskStatus;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

public class SubTaskStateMachine {

    private static final Map<SubTaskStatus, Set<SubTaskStatus>> TRANSITIONS = new EnumMap<>(SubTaskStatus.class);

    static {
        TRANSITIONS.put(SubTaskStatus.PENDING,     Set.of(SubTaskStatus.ASSIGNED, SubTaskStatus.CANCELLED));
        TRANSITIONS.put(SubTaskStatus.ASSIGNED,     Set.of(SubTaskStatus.IN_PROGRESS, SubTaskStatus.PENDING));
        TRANSITIONS.put(SubTaskStatus.IN_PROGRESS,  Set.of(SubTaskStatus.REVIEW, SubTaskStatus.BLOCKED));
        TRANSITIONS.put(SubTaskStatus.REVIEW,       Set.of(SubTaskStatus.DONE, SubTaskStatus.REWORK));
        TRANSITIONS.put(SubTaskStatus.REWORK,       Set.of(SubTaskStatus.IN_PROGRESS));
        TRANSITIONS.put(SubTaskStatus.BLOCKED,      Set.of(SubTaskStatus.PENDING));
        TRANSITIONS.put(SubTaskStatus.DONE,        Set.of());
        TRANSITIONS.put(SubTaskStatus.CANCELLED,   Set.of());
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
