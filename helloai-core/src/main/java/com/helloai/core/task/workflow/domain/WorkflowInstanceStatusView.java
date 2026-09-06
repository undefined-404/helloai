package com.helloai.core.task.workflow.domain;

import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.WorkflowInstanceStatus;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Workflow 实例聚合状态视图（N-001，C1-S3，纯查询投影）。
 *
 * <p>由 sub_task 状态现算（D6-2 方案 A），展示用；不含状态机、无约束力。</p>
 */
@Data
@Builder
public class WorkflowInstanceStatusView {

    /** 实例 ID。 */
    private Long instanceId;

    /** 物化出的任务 ID。 */
    private Long taskId;

    /** 聚合状态。 */
    private WorkflowInstanceStatus status;

    /** 完成节点数。 */
    private int doneCount;

    /** 节点总数。 */
    private int totalCount;

    /** 判定原因（如 SLA_TIMEOUT / DEAD_LETTER / ALL_DONE / CANCELED / RUNNING）。 */
    private String reason;

    /** 节点状态快照：nodeKey → SubTaskStatus.name()（顺序与定义一致）。 */
    private Map<String, String> nodeStatuses;
}
