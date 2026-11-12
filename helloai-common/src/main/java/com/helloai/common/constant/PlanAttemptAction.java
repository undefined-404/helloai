package com.helloai.common.constant;

/**
 * 拆解操作的类型（`TD-16b`）。
 *
 * <p>与 {@link PlanAttemptStatus} 同表存储：`action` 说明「这是哪类操作」，
 * `status` 说明「这次尝试到哪一步」。二者组合才能回答完整问题——
 * 只按 `status` 统计会把「拆解失败」和「确认失败」混在一起。</p>
 *
 * <p>取值与数据库 {@code chk_plan_attempt_action} 约束逐字一致，增删须同步迁移。</p>
 */
public enum PlanAttemptAction {

    /** 整批首拆：{@code task.status = PLANNING} 触发。 */
    DECOMPOSE,

    /** 单条草案重生成（同粒度重写四要素）。 */
    REGENERATE,

    /** 草案确认转正（唯一放行口）。 */
    CONFIRM,

    /**
     * 整体废除：整轮草案作废 + 子任务按认领状态分流。
     *
     * <p><b>当前无写入点</b>：`ADR-008` §19 取代原「整体废除 + 多轮重写」模型
     * （重构改为派生独立 Task，同一 Task 不再有第二轮草案），本值保留为历史值。</p>
     */
    ABANDON,

    /**
     * 草案期/执行期派生（`ADR-008`）：把一条草案或一个子任务拆出去，生成独立 Task Y。
     *
     * <p>与旧「同 Task 内展开」的区别：本动作不改动当前 Task 内的草案集合，
     * 只创建一个新 Task；失败时可整体放弃后再派生（{@code derivation_round} 递增）。</p>
     */
    DERIVE,

    /**
     * 执行期重构（`ADR-008`）：子任务进入 {@code RESTRUCTURING} 后派生新的执行方案。
     *
     * <p>比 {@link #DERIVE} 多一步「父 SubTask 状态转 {@code RESTRUCTURING} + 重建
     * 需求上下文」；动作分列是为了让 attempt 表能区分「草案期拆解」与「执行期返工」，
     * 否则按动作统计会把两类完全不同的失败混在一起。</p>
     */
    RESTRUCTURE;

    /** 值域校验：null / 未知值返回 {@code false}。 */
    public static boolean isKnown(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        for (PlanAttemptAction v : values()) {
            if (v.name().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
