package com.helloai.api.controller;

import com.helloai.api.dto.workflow.WorkflowInstanceStatusResponse;
import com.helloai.common.base.R;
import com.helloai.common.constant.WorkflowInstanceStatus;
import com.helloai.core.task.workflow.domain.WorkflowInstanceStatusView;
import com.helloai.core.task.workflow.service.WorkflowInstanceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link WorkflowInstanceController} C1-S3 单元测试：聚合状态查询端点。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowInstanceController 实例状态聚合端点（C1-S3）")
class WorkflowInstanceControllerTest {

    @Mock
    private WorkflowInstanceService workflowInstanceService;

    @InjectMocks
    private WorkflowInstanceController controller;

    @Test
    @DisplayName("status：id 透传 service，聚合视图映射为响应")
    void shouldReturnAggregatedStatus() {
        WorkflowInstanceStatusView view = WorkflowInstanceStatusView.builder()
                .instanceId(20L)
                .taskId(100L)
                .status(WorkflowInstanceStatus.DONE)
                .doneCount(2)
                .totalCount(2)
                .reason("ALL_DONE")
                .nodeStatuses(Map.of("contract", "DONE", "implement", "DONE"))
                .build();
        when(workflowInstanceService.aggregateStatus(20L)).thenReturn(view);

        R<WorkflowInstanceStatusResponse> resp = controller.status(20L);

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData().getInstanceId()).isEqualTo(20L);
        assertThat(resp.getData().getTaskId()).isEqualTo(100L);
        assertThat(resp.getData().getStatus()).isEqualTo("DONE");
        assertThat(resp.getData().getDoneCount()).isEqualTo(2);
        assertThat(resp.getData().getTotalCount()).isEqualTo(2);
        assertThat(resp.getData().getReason()).isEqualTo("ALL_DONE");
        assertThat(resp.getData().getNodeStatuses()).containsEntry("contract", "DONE");
        verify(workflowInstanceService).aggregateStatus(20L);
        verifyNoMoreInteractions(workflowInstanceService);
    }
}
