package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.core.entity.Agent;
import com.helloai.core.entity.SubTask;
import com.helloai.core.service.AgentService;
import com.helloai.core.service.SubTaskService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OptionalEnumFilterControllerTest {

    @Test
    void adminAgentList_allowsMissingRoleAndStatusFilters() {
        AgentService agentService = mock(AgentService.class);
        when(agentService.page(anyPage(), anyAgentWrapper())).thenReturn(new Page<Agent>(1, 20));
        AdminAgentController controller = new AdminAgentController(agentService);

        assertThatCode(() -> {
            var response = controller.list(1, 20, null, null, null, null, null);
            assertThat(response.getCode()).isEqualTo(200);
            assertThat(response.getData().getList()).isEmpty();
        }).doesNotThrowAnyException();
    }

    @Test
    void subTaskList_allowsMissingStatusFilter() {
        SubTaskService subTaskService = mock(SubTaskService.class);
        when(subTaskService.list(anySubTaskWrapper())).thenReturn(List.<SubTask>of());
        SubTaskController controller = new SubTaskController(subTaskService);

        assertThatCode(() -> {
            var response = controller.list(null, null, null);
            assertThat(response.getCode()).isEqualTo(200);
            assertThat(response.getData()).isEmpty();
        }).doesNotThrowAnyException();
    }

    @SuppressWarnings("unchecked")
    private static Page<Agent> anyPage() {
        return any(Page.class);
    }

    @SuppressWarnings("unchecked")
    private static LambdaQueryWrapper<Agent> anyAgentWrapper() {
        return any(LambdaQueryWrapper.class);
    }

    @SuppressWarnings("unchecked")
    private static LambdaQueryWrapper<SubTask> anySubTaskWrapper() {
        return any(LambdaQueryWrapper.class);
    }
}
