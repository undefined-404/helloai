package com.helloai.core.task.workflow.aggregator;

import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.common.constant.WorkflowInstanceStatus;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.workflow.domain.WorkflowInstanceStatusView;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workflow 实例状态聚合器（N-001，C1-S3，纯函数可单测）。
 *
 * <p>由 sub_task 状态纯查询聚合出实例状态（D6-2 方案 A，不落权威列）——派生态投影，
 * 绝不反向约束 task/sub_task 操作（D6-3 反锁禁令）。聚合规则：</p>
 * <ol>
 *   <li>全部节点 DONE 且 task 终态 DONE → {@code DONE}（ALL_DONE）；</li>
 *   <li>全部节点 CANCELLED → {@code CANCELLED}（CANCELED）；</li>
 *   <li>存在 DEAD_LETTER（重分配熔断入人工池）→ {@code FAILED}（DEAD_LETTER）；</li>
 *   <li>实例级 SLA 超时（task.slaMinutes 非空且已过 deadline 仍有未终态节点）→ {@code FAILED}（SLA_TIMEOUT）；</li>
 *   <li>其余（含 BLOCKED 等待人工 / REWORK / 执行中）→ {@code RUNNING}。</li>
 * </ol>
 */
public final class WorkflowInstanceStatusAggregator {

    private WorkflowInstanceStatusAggregator() {
    }

    /**
     * 聚合实例状态。
     *
     * @param instanceId 实例 ID（透传给视图，展示用）
     * @param subTasks   物化出的全部 sub_task（节点）
     * @param task       物化出的主任务
     * @param now        聚合时间点
     */
    public static WorkflowInstanceStatusView aggregate(Long instanceId, List<SubTask> subTasks,
                                                       Task task, OffsetDateTime now) {
        int total = subTasks == null ? 0 : subTasks.size();
        Map<String, String> nodeStatuses = new LinkedHashMap<>();
        int doneCount = 0;
        int cancelledCount = 0;
        int deadLetterCount = 0;
        for (SubTask st : subTasks) {
            SubTaskStatus s = st.getStatus() != null ? st.getStatus() : SubTaskStatus.PENDING;
            nodeStatuses.put(nodeKey(st), s.name());
            if (s == SubTaskStatus.DONE) {
                doneCount++;
            } else if (s == SubTaskStatus.CANCELLED) {
                cancelledCount++;
            } else if (s == SubTaskStatus.DEAD_LETTER) {
                deadLetterCount++;
            }
        }

        WorkflowInstanceStatusView.WorkflowInstanceStatusViewBuilder view =
                WorkflowInstanceStatusView.builder()
                        .instanceId(instanceId)
                        .taskId(task != null ? task.getId() : null)
                        .doneCount(doneCount)
                        .totalCount(total)
                        .nodeStatuses(nodeStatuses);

        if (total == 0) {
            return view.status(WorkflowInstanceStatus.RUNNING).reason("EMPTY").build();
        }
        if (doneCount == total && task != null && task.getStatus() == TaskStatus.DONE) {
            return view.status(WorkflowInstanceStatus.DONE).reason("ALL_DONE").build();
        }
        if (cancelledCount == total) {
            return view.status(WorkflowInstanceStatus.CANCELLED).reason("CANCELED").build();
        }
        if (deadLetterCount > 0) {
            return view.status(WorkflowInstanceStatus.FAILED).reason("DEAD_LETTER").build();
        }
        if (slaTimeout(task, now) && doneCount + cancelledCount < total) {
            return view.status(WorkflowInstanceStatus.FAILED).reason("SLA_TIMEOUT").build();
        }
        return view.status(WorkflowInstanceStatus.RUNNING).reason("IN_PROGRESS").build();
    }

    /** 实例级 SLA 超时：task.slaMinutes 非空 且 now 已过 createTime + slaMinutes。 */
    private static boolean slaTimeout(Task task, OffsetDateTime now) {
        if (task == null || task.getSlaMinutes() == null || task.getCreateTime() == null) {
            return false;
        }
        OffsetDateTime deadline = task.getCreateTime().plusMinutes(task.getSlaMinutes());
        return now != null && now.isAfter(deadline);
    }

    /** 节点标识：context.workflow.nodeKey（单顶级键），缺失兜底用 id。 */
    @SuppressWarnings("unchecked")
    private static String nodeKey(SubTask st) {
        if (st.getContext() != null) {
            Object wf = st.getContext().get("workflow");
            if (wf instanceof Map<?, ?> meta) {
                Object key = meta.get("nodeKey");
                if (key != null) {
                    return String.valueOf(key);
                }
            }
        }
        return st.getId() != null ? String.valueOf(st.getId()) : "?";
    }
}
