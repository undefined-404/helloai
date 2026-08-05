package com.helloai.common.constant;

/**
 * task_iteration 表字段常量（V42）。
 *
 * <p>消除 Service 层字符串硬编码，对齐 CODE_STYLE §1 总体原则：
 * "状态常量：禁止硬编码，统一使用枚举类"。</p>
 *
 * <p>task_type / review_result 的值受 DB CHECK 约束管理，此处仅提供可引用的常量名。</p>
 */
public final class TaskIterationConst {

    private TaskIterationConst() {
    }

    /** 任务类型：开发 */
    public static final String TYPE_DEVELOPMENT = "DEVELOPMENT";
    /** 任务类型：测试 */
    public static final String TYPE_TESTING = "TESTING";
    /** 任务类型：规划 */
    public static final String TYPE_PLANNING = "PLANNING";
    /** 任务类型：其他 */
    public static final String TYPE_OTHER = "OTHER";

    /** 审核结果：通过 */
    public static final String REVIEW_PASSED = "PASSED";
    /** 审核结果：驳回 */
    public static final String REVIEW_REJECTED = "REJECTED";
}
