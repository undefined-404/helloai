package com.helloai.core.planner.policy;

/**
 * Planner 拆解粒度三档（G-010 能力感知与自适应粒度）。
 *
 * <p>由 {@link PlannerGranularityResolver} 按执行者画像 + 任务难度定位；
 * 映射为 {@code planner-decompose.md} 中的粒度指令段。粒度不是越细越好：
 * 确定执行者强（外部 CLI_CLIENT）才放宽为 COARSE，不确定执行者不默认细拆。
 */
public enum PlannerGranularity {
    /** 细：有序步骤 + 每步指定 Skill + 输入/输出契约 + DoD。 */
    FINE,
    /** 中（≈现状）：四要素 + 依赖 + 契约先行评估。 */
    STANDARD,
    /** 粗：目标 + constraints + 验收 + DoD（止损回退 + 幂等）。 */
    COARSE
}