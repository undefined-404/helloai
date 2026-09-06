package com.helloai.core.task.workflow.aggregator;

import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.common.constant.WorkflowInstanceStatus;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.workflow.domain.WorkflowInstanceStatusView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WorkflowInstanceStatusAggregator} 纯函数单测（N-001，C1-S3）。
 *
 * <p>覆盖聚合规则：ALL_DONE / CANCELED / DEAD_LETTER / SLA_TIMEOUT / IN_PROGRESS / EMPTY，
 * 以及 nodeKey 提取（context 单顶级键 workflow.nodeKey，缺失兜底 id）。</p>
 */
@DisplayName("WorkflowInstanceStatusAggregator 实例状态聚合（C1-S3）")
class WorkflowInstanceStatusAggregatorTest {

    private static final long INSTANCE_ID = 1L;
    private static final long TASK_ID = 100L;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-05T12:00:00+08:00");

    private SubTask subTask(long id, String nodeKey, SubTaskStatus status) {
        SubTask st = new SubTask();
        st.setId(id);
        st.setStatus(status);
        st.setContext(Map.of("workflow", Map.of("nodeKey", nodeKey)));
        return st;
    }

    private Task task(TaskStatus status, Integer slaMinutes, OffsetDateTime createTime) {
        Task t = new Task();
        t.setId(TASK_ID);
        t.setStatus(status);
        t.setSlaMinutes(slaMinutes);
        t.setCreateTime(createTime);
        return t;
    }

    @Nested
    @DisplayName("终态判定")
    class Terminal {

        @Test
        @DisplayName("全部节点 DONE 且 task DONE → DONE，进度全满")
        void shouldAggregateDone() {
            WorkflowInstanceStatusView view = WorkflowInstanceStatusAggregator.aggregate(INSTANCE_ID,
                    List.of(subTask(1, "contract", SubTaskStatus.DONE),
                            subTask(2, "implement", SubTaskStatus.DONE)),
                    task(TaskStatus.DONE, 120, NOW.minusHours(1)), NOW);

            assertThat(view.getStatus()).isEqualTo(WorkflowInstanceStatus.DONE);
            assertThat(view.getReason()).isEqualTo("ALL_DONE");
            assertThat(view.getDoneCount()).isEqualTo(2);
            assertThat(view.getTotalCount()).isEqualTo(2);
            assertThat(view.getTaskId()).isEqualTo(TASK_ID);
            assertThat(view.getNodeStatuses()).containsEntry("contract", "DONE");
        }

        @Test
        @DisplayName("节点全 DONE 但 task 未 DONE → RUNNING（task 终态是权威）")
        void shouldRequireTaskDone() {
            WorkflowInstanceStatusView view = WorkflowInstanceStatusAggregator.aggregate(INSTANCE_ID,
                    List.of(subTask(1, "a", SubTaskStatus.DONE)),
                    task(TaskStatus.IN_PROGRESS, null, NOW.minusHours(1)), NOW);

            assertThat(view.getStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
        }

        @Test
        @DisplayName("全部节点 CANCELLED → CANCELLED")
        void shouldAggregateCancelled() {
            WorkflowInstanceStatusView view = WorkflowInstanceStatusAggregator.aggregate(INSTANCE_ID,
                    List.of(subTask(1, "a", SubTaskStatus.CANCELLED),
                            subTask(2, "b", SubTaskStatus.CANCELLED)),
                    task(TaskStatus.IN_PROGRESS, null, NOW.minusHours(1)), NOW);

            assertThat(view.getStatus()).isEqualTo(WorkflowInstanceStatus.CANCELLED);
            assertThat(view.getReason()).isEqualTo("CANCELED");
        }

        @Test
        @DisplayName("存在 DEAD_LETTER → FAILED（熔断人工池）")
        void shouldAggregateDeadLetter() {
            WorkflowInstanceStatusView view = WorkflowInstanceStatusAggregator.aggregate(INSTANCE_ID,
                    List.of(subTask(1, "a", SubTaskStatus.DONE),
                            subTask(2, "b", SubTaskStatus.DEAD_LETTER)),
                    task(TaskStatus.IN_PROGRESS, null, NOW.minusHours(1)), NOW);

            assertThat(view.getStatus()).isEqualTo(WorkflowInstanceStatus.FAILED);
            assertThat(view.getReason()).isEqualTo("DEAD_LETTER");
        }
    }

    @Nested
    @DisplayName("SLA 超时与运行中")
    class SlaAndRunning {

        @Test
        @DisplayName("实例级 SLA 超时且仍有未终态节点 → FAILED")
        void shouldAggregateSlaTimeout() {
            WorkflowInstanceStatusView view = WorkflowInstanceStatusAggregator.aggregate(INSTANCE_ID,
                    List.of(subTask(1, "a", SubTaskStatus.DONE),
                            subTask(2, "b", SubTaskStatus.ASSIGNED)),
                    task(TaskStatus.IN_PROGRESS, 60, NOW.minusHours(2)), NOW);

            assertThat(view.getStatus()).isEqualTo(WorkflowInstanceStatus.FAILED);
            assertThat(view.getReason()).isEqualTo("SLA_TIMEOUT");
        }

        @Test
        @DisplayName("SLA 未超时 / 无 slaMinutes / 已终态 → 不判 SLA_TIMEOUT")
        void shouldNotJudgeSlaTimeout() {
            // 未超时（deadline 未过）
            WorkflowInstanceStatusView within = WorkflowInstanceStatusAggregator.aggregate(INSTANCE_ID,
                    List.of(subTask(1, "a", SubTaskStatus.ASSIGNED)),
                    task(TaskStatus.IN_PROGRESS, 60, NOW.minusMinutes(10)), NOW);
            assertThat(within.getStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
            assertThat(within.getReason()).isEqualTo("IN_PROGRESS");

            // 无 slaMinutes
            WorkflowInstanceStatusView noSla = WorkflowInstanceStatusAggregator.aggregate(INSTANCE_ID,
                    List.of(subTask(1, "a", SubTaskStatus.ASSIGNED)),
                    task(TaskStatus.IN_PROGRESS, null, NOW.minusHours(2)), NOW);
            assertThat(noSla.getStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
        }

        @Test
        @DisplayName("节点执行中 / BLOCKED 等待人工 → RUNNING（fail-close 可恢复，非终态）")
        void shouldStayRunningOnBlocked() {
            WorkflowInstanceStatusView view = WorkflowInstanceStatusAggregator.aggregate(INSTANCE_ID,
                    List.of(subTask(1, "a", SubTaskStatus.DONE),
                            subTask(2, "b", SubTaskStatus.BLOCKED)),
                    task(TaskStatus.IN_PROGRESS, null, NOW.minusHours(1)), NOW);

            assertThat(view.getStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
            assertThat(view.getReason()).isEqualTo("IN_PROGRESS");
        }

        @Test
        @DisplayName("空节点列表 → RUNNING（EMPTY）")
        void shouldAggregateEmpty() {
            WorkflowInstanceStatusView view = WorkflowInstanceStatusAggregator.aggregate(INSTANCE_ID,
                    List.of(), task(TaskStatus.IN_PROGRESS, null, NOW), NOW);

            assertThat(view.getStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
            assertThat(view.getReason()).isEqualTo("EMPTY");
        }
    }

    @Nested
    @DisplayName("nodeKey 提取")
    class NodeKey {

        @Test
        @DisplayName("context.workflow.nodeKey 提取；缺失兜底 id")
        void shouldExtractNodeKey() {
            SubTask noContext = new SubTask();
            noContext.setId(9L);
            noContext.setStatus(SubTaskStatus.DONE);

            WorkflowInstanceStatusView view = WorkflowInstanceStatusAggregator.aggregate(INSTANCE_ID,
                    List.of(noContext), task(TaskStatus.DONE, null, NOW.minusHours(1)), NOW);

            assertThat(view.getNodeStatuses()).containsKey("9"); // 兜底 id
        }
    }
}
