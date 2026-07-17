package com.helloai.common.constant;

/**
 * Agent 值班租约工作模式。
 *
 * <p>N12 P1 STRICT 独占报锁（A2 第 1 段）落地：值班时声明工作模式，
 * STRICT 模式下平台调度器不把该 Agent 列入他人失败/熔断后的替补池抢派。
 *
 * <p>DB 列 {@code agent_duty_lease.work_mode} 仍为 String，本枚举只做
 * 入参校验 + 调用方语义转换；历史数据可能为 null/空串/旧自定义值，
 * 读取时统一按 {@link #AUTO} 处理（宽松兼容，不阻断历史 lease）。</p>
 */
public enum WorkMode {

    /**
     * 默认模式：可正常参与任务派发与他人失败后的替补池。
     */
    AUTO,

    /**
     * 独占报锁：值班期间只接自己被初始指派的任务，
     * 不参与他人失败/熔断后的 pickAlternative 替补池抢派。
     *
     * <p>仅影响 {@code AgentSelector.pickAlternative} 的候选过滤：
     * STRICT Agent 仍然可以被初始分配（preferred）和被直接分配（assignedAgent=
     * thisAgent）的任务命中，只是不再被平台"挑去顶别人的班"。</p>
     */
    STRICT;

    /**
     * 字符串解析（大小写不敏感；null/blank 视为 AUTO）。
     *
     * <p>宽松兼容策略：null / 空串 / 不识别值统一视作 AUTO，
     * 不抛异常。STRICT 必须在注册/打卡时显式声明才会生效。</p>
     *
     * @param raw 原始字符串（来自 MCP checkIn 入参、DB 历史值等）
     * @return 解析后的枚举（绝不返回 null）
     */
    public static WorkMode lenientParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        for (WorkMode m : values()) {
            if (m.name().equalsIgnoreCase(raw.trim())) {
                return m;
            }
        }
        return AUTO;
    }

    /**
     * 严格解析（大小写不敏感；null/blank 视为 AUTO；其他非法值抛 IllegalArgumentException）。
     *
     * <p>用于入参校验：MCP checkIn 工具收到非法 workMode 时立即拒绝，
     * 防止历史脏数据通过 lenient 路径把 STRICT 默默降级成 AUTO。</p>
     *
     * @throws IllegalArgumentException 当 raw 既不是 null/blank 也不在枚举里
     */
    public static WorkMode strictParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        for (WorkMode m : values()) {
            if (m.name().equalsIgnoreCase(raw.trim())) {
                return m;
            }
        }
        throw new IllegalArgumentException(
                "unknown workMode: '" + raw + "', valid values: AUTO, STRICT");
    }
}