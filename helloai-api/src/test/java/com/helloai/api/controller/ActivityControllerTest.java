package com.helloai.api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.api.dto.PageResult;
import com.helloai.common.base.R;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.entity.ActivityLog;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.ActivityLogService;
import com.helloai.core.agent.service.AgentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ActivityController} 活动流列表增强单元测试（2026-10-22）。
 *
 * <p>核心断言：列表为每条活动日志补充 {@code agentName} / {@code agentAccessType}；
 * Agent 画像用 {@code listProfilesByIds} <b>批量</b>投影（一次调用，避免 N+1）；
 * Agent 缺失时字段为 null 而非抛异常。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityController 活动流列表（Agent 名称 + 接入类型增强）")
class ActivityControllerTest {

    @Mock
    private ActivityLogService activityLogService;

    @Mock
    private AgentService agentService;

    @InjectMocks
    private ActivityController controller;

    private static final Long AGENT_ID = 2088624140014718977L;
    private static final Long ORPHAN_AGENT_ID = 999L;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-22T15:43:26+08:00");

    private ActivityLog log(Long agentId, String action) {
        ActivityLog l = new ActivityLog();
        l.setId(1L);
        l.setAgentId(agentId);
        l.setAction(action);
        l.setLevel("INFO");
        l.setSource("platform");
        l.setDetail(Map.of("resultId", "r-1"));
        l.setCreateTime(NOW);
        return l;
    }

    private AgentProfileSnapshot profile(Long id, String name, AgentAccessType type) {
        return AgentProfileSnapshot.builder()
                .id(id)
                .name(name)
                .accessType(type)
                .build();
    }

    @Test
    @DisplayName("全量列表：补 agentName / agentAccessType，且批量投影只查一次")
    @SuppressWarnings("unchecked")
    void listFull_decoratesAgentNameAndAccessType() {
        Page<ActivityLog> page = new Page<>(1, 30);
        page.setRecords(List.of(log(AGENT_ID, "delivery")));
        page.setTotal(1);

        when(activityLogService.list(any(), any(), anyInt(), anyInt())).thenReturn(page);
        when(agentService.listProfilesByIds(anyCollection()))
                .thenReturn(List.of(profile(AGENT_ID, "trae-excutor", AgentAccessType.CLI_CLIENT)));

        R<?> resp = controller.list(null, null, 0, 30);

        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.getData();
        assertThat(items).hasSize(1);
        Map<String, Object> view = items.get(0);
        assertThat(view.get("agentName")).isEqualTo("trae-excutor");
        assertThat(view.get("agentAccessType")).isEqualTo(AgentAccessType.CLI_CLIENT);
        // 原有字段保持可消费
        assertThat(view.get("action")).isEqualTo("delivery");
        assertThat(view.get("level")).isEqualTo("INFO");
        assertThat(view.get("source")).isEqualTo("platform");
        // 批量投影：一次调用，无 N+1
        verify(agentService, times(1)).listProfilesByIds(anyCollection());
    }

    @Test
    @DisplayName("分页列表：PageResult 内每条同样带 agent 信息")
    @SuppressWarnings("unchecked")
    void listPaged_decoratesAgentInfo() {
        Page<ActivityLog> page = new Page<>(1, 30);
        page.setRecords(List.of(log(AGENT_ID, "blocked")));
        page.setTotal(1);

        when(activityLogService.list(any(), any(), anyInt(), anyInt())).thenReturn(page);
        when(agentService.listProfilesByIds(anyCollection()))
                .thenReturn(List.of(profile(AGENT_ID, "local-e2e-executor", AgentAccessType.CLI_CLIENT)));

        R<?> resp = controller.list(null, null, 1, 30);

        PageResult<Map<String, Object>> result = (PageResult<Map<String, Object>>) resp.getData();
        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getList()).hasSize(1);
        assertThat(result.getList().get(0).get("agentName")).isEqualTo("local-e2e-executor");
        assertThat(result.getList().get(0).get("agentAccessType")).isEqualTo(AgentAccessType.CLI_CLIENT);
    }

    @Test
    @DisplayName("Agent 画像缺失：agentName/agentAccessType 为 null，不抛异常")
    void listOrphanAgent_noNullPointer() {
        Page<ActivityLog> page = new Page<>(1, 30);
        page.setRecords(List.of(log(ORPHAN_AGENT_ID, "delivery")));
        page.setTotal(1);

        when(activityLogService.list(any(), any(), anyInt(), anyInt())).thenReturn(page);
        // 已删除 Agent：投影返回空集合
        when(agentService.listProfilesByIds(anyCollection())).thenReturn(List.of());

        R<?> resp = controller.list(null, null, 0, 30);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.getData();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("agentName")).isNull();
        assertThat(items.get(0).get("agentAccessType")).isNull();
    }

    @Test
    @DisplayName("记录全无 agentId：跳过 Agent 查询，不发起批量投影")
    void listNoAgentIds_skipsProfileQuery() {
        Page<ActivityLog> page = new Page<>(1, 30);
        page.setRecords(List.of(log(null, "delivery")));
        page.setTotal(1);

        when(activityLogService.list(any(), any(), anyInt(), anyInt())).thenReturn(page);

        controller.list(null, null, 0, 30);

        verify(agentService, times(0)).listProfilesByIds(anyCollection());
    }
}
