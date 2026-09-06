package com.helloai.common.constant;

/**
 * Workflow 实例聚合状态（N-001，C1-S3）。
 *
 * <p>由 sub_task 状态纯查询聚合而来（D6-2 方案 A）：不落权威列，每次现算；
 * 是派生态投影，绝不反向约束 task/sub_task 操作（D6-3 反锁禁令）。</p>
 */
public enum WorkflowInstanceStatus {

    /** 运行中：存在未终态节点（含 BLOCKED 等待人工介入 / REWORK 返工中）。 */
    RUNNING,

    /** 已完成：全部节点 DONE 且 task 终态 DONE。 */
    DONE,

    /** 失败：存在 DEAD_LETTER（熔断人工池）或实例级 SLA 超时。 */
    FAILED,

    /** 已取消：全部节点 CANCELLED。 */
    CANCELLED
}
