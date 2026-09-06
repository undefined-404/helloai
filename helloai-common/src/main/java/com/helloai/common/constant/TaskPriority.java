package com.helloai.common.constant;

/**
 * 任务优先级（N-006，C4-S1）。
 *
 * <p>值域对齐 {@code sub_task.priority}（VARCHAR：HIGH/MEDIUM/LOW）。静态解析收口：
 * 非法/缺失回落 MEDIUM（与既有 {@code PlannerDecomposeAsyncServiceImpl.normalizePriority}
 * 语义一致）。档位 {@code rank}（HIGH=3/MEDIUM=2/LOW=1）供调度出队排序与 aging 计算。</p>
 */
public enum TaskPriority {

    HIGH(3),
    MEDIUM(2),
    LOW(1);

    private final int rank;

    TaskPriority(int rank) {
        this.rank = rank;
    }

    /** 排序档位：HIGH=3 / MEDIUM=2 / LOW=1。 */
    public int rank() {
        return rank;
    }

    /** 默认优先级。 */
    public static TaskPriority DEFAULT() {
        return MEDIUM;
    }

    /** 归一化：null/空白/非法值回落 MEDIUM；返回枚举 name() 字符串（与表列 VARCHAR 对齐）。 */
    public static String normalize(String value) {
        return parse(value).name();
    }

    /** 解析：null/空白/非法值回落 MEDIUM。 */
    public static TaskPriority parse(String value) {
        if (value == null || value.isBlank()) {
            return MEDIUM;
        }
        String upper = value.trim().toUpperCase();
        for (TaskPriority p : values()) {
            if (p.name().equals(upper)) {
                return p;
            }
        }
        return MEDIUM;
    }
}
