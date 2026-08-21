package com.helloai.core.task.spec;

import com.helloai.core.task.entity.TaskRunningSpecEntity;
import com.helloai.core.task.mapper.TaskExecutionRecordMapper;
import com.helloai.core.task.mapper.TaskRunningSpecMapper;
import com.helloai.core.task.service.impl.TaskRunningSpecTableServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TaskRunningSpecTableServiceImpl 契约先行拆解（Phase 2）增补测试：
 * updateContract 透传 / assembleDomain 组装契约 / Prompt 渲染「## 任务契约」节。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TaskRunningSpecTableService (contract)")
class TaskRunningSpecTableServiceTest {

    @Mock
    private TaskRunningSpecMapper specMapper;

    @Mock
    private TaskExecutionRecordMapper recordMapper;

    @InjectMocks
    private TaskRunningSpecTableServiceImpl tableService;

    @Test
    @DisplayName("should delegate updateContract to specMapper.updateContract")
    void shouldDelegateUpdateContract() {
        Map<String, Object> contract = Map.of(
                "subTaskId", 11L, "title", "契约定义", "content", "接口签名");

        tableService.updateContract(100L, contract);

        verify(specMapper).updateContract(eq(100L), eq(contract));
    }

    @Test
    @DisplayName("should assemble contract from entity in getOrCreate")
    void shouldAssembleContractFromEntity() {
        TaskRunningSpecEntity entity = new TaskRunningSpecEntity();
        entity.setTaskId(100L);
        entity.setVersion(1);
        entity.setBaseline(Map.of("goal", "目标"));
        entity.setContextSummary("已完成 1 个子任务");
        entity.setContract(Map.of("title", "接口契约", "content", "POST /api/orders"));
        when(specMapper.selectByTaskId(100L)).thenReturn(entity);
        when(recordMapper.selectByTaskId(100L)).thenReturn(List.of());

        TaskRunningSpec spec = tableService.getOrCreate(100L);

        assertThat(spec.contract()).isNotNull()
                .containsEntry("title", "接口契约")
                .containsEntry("content", "POST /api/orders");
    }

    @Test
    @DisplayName("should render contract section in executor prompt when contract present")
    void shouldRenderContractSectionWhenPresent() {
        TaskRunningSpecEntity entity = new TaskRunningSpecEntity();
        entity.setTaskId(100L);
        entity.setVersion(1);
        entity.setBaseline(Map.of("goal", "目标"));
        entity.setContract(Map.of("title", "接口契约 v1", "content", "错误码表：400/500"));
        when(specMapper.selectByTaskId(100L)).thenReturn(entity);
        when(recordMapper.selectByTaskId(100L)).thenReturn(List.of());

        String section = tableService.buildExecutorPromptSection(100L);

        assertThat(section)
                .contains("## 任务契约")
                .contains("契约来源：接口契约 v1")
                .contains("错误码表：400/500");
    }

    @Test
    @DisplayName("should omit contract section when contract absent")
    void shouldOmitContractSectionWhenAbsent() {
        TaskRunningSpecEntity entity = new TaskRunningSpecEntity();
        entity.setTaskId(100L);
        entity.setVersion(1);
        entity.setBaseline(Map.of("goal", "目标"));
        when(specMapper.selectByTaskId(100L)).thenReturn(entity);
        when(recordMapper.selectByTaskId(100L)).thenReturn(List.of());

        String section = tableService.buildExecutorPromptSection(100L);

        assertThat(section).doesNotContain("## 任务契约");
    }
}
