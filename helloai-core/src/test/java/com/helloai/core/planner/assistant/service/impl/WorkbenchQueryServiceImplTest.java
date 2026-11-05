package com.helloai.core.planner.assistant.service.impl;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.LlmProviderCatalogService;
import com.helloai.core.planner.assistant.service.AssistantProfileService;
import com.helloai.core.planner.assistant.service.AssistantProfileService.AssistantSnapshot;
import com.helloai.core.planner.assistant.service.MyArtifactsQueryService;
import com.helloai.core.planner.assistant.service.WorkbenchQueryService;
import com.helloai.core.planner.assistant.service.WorkbenchQueryService.ActiveAgent;
import com.helloai.core.planner.assistant.service.WorkbenchQueryService.Workbench;
import com.helloai.core.shared.util.UserContext;
import com.helloai.core.task.service.SubTaskQueryService;
import com.helloai.core.task.service.SubTaskQueryService.ActiveAgentLoad;
import com.helloai.core.task.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkbenchQueryServiceImpl} 单测（PA-4 活跃 Agent 改「名册 + 状态」，2026-11-03；
 * 2026-11-05 增加心跳可见性规则与模型候选转发）。
 *
 * <p>覆盖五件事：
 * <ol>
 *   <li>名册口径：只取 ACTIVE 的执行 / 复核角色 Agent，且不受「当前是否有人在跑任务」影响；</li>
 *   <li>状态派生：执行中 &gt; 审核中 &gt; 待命，排序按状态 → 在跑条数 → 名称；</li>
 *   <li>可见性：内部 LLM 常驻可见（不看心跳），外部 Agent 须心跳 ONLINE / IDLE，
 *       被隐藏的条数单独回传（前端据此提示，不静默消失）；</li>
 *   <li>展示条数上限截断；</li>
 *   <li>模型候选集只做转发，不在本域重算有效性。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class WorkbenchQueryServiceImplTest {

    private static final long USER_ID = 2072852029591150593L;

    @Mock
    private AssistantProfileService assistantProfileService;
    @Mock
    private TaskService taskService;
    @Mock
    private SubTaskQueryService subTaskQueryService;
    @Mock
    private AgentService agentService;
    @Mock
    private MyArtifactsQueryService myArtifactsQueryService;
    @Mock
    private LlmProviderCatalogService llmProviderCatalogService;

    private WorkbenchQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        UserContext.set(USER_ID);
        service = new WorkbenchQueryServiceImpl(assistantProfileService, taskService,
                subTaskQueryService, agentService, myArtifactsQueryService,
                llmProviderCatalogService, new com.helloai.common.config.AssistantChatProperties());
        lenient().when(assistantProfileService.getActiveByUser(USER_ID)).thenReturn(profile());
        // 共享夹具的「空数据」默认值：多数用例走 aggregate()，但转发类用例不碰聚合，
        // 用 lenient 避免为凑覆盖率在无关用例里硬塞一次 aggregate()。
        lenient().when(subTaskQueryService.overviewForOwner(USER_ID))
                .thenReturn(SubTaskQueryService.TaskOverview.EMPTY);
        lenient().when(taskService.listActiveTasksForOwner(anyInt())).thenReturn(List.of());
        lenient().when(myArtifactsQueryService.conversationArtifacts(anyInt())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private AssistantSnapshot profile() {
        return new AssistantSnapshot("AI 助手", "你的个人助理", "deepseek-v3",
                List.of(), "你好");
    }

    /** 心跳在线的 Agent（外部 Agent 进列表的必要条件）。 */
    private AgentProfileSnapshot agent(long id, String name, AgentRole role, AgentAccessType access) {
        return agent(id, name, role, access, AgentOnlineStatus.ONLINE);
    }

    private AgentProfileSnapshot agent(long id, String name, AgentRole role, AgentAccessType access,
                                       AgentOnlineStatus online) {
        return AgentProfileSnapshot.builder()
                .id(id)
                .name(name)
                .role(role)
                .accessType(access)
                .status(AgentStatus.ACTIVE)
                .onlineStatus(online)
                .build();
    }

    @Test
    void 名册含空闲Agent_不再因无人跑任务而整块消失() {
        when(agentService.listActiveProfiles()).thenReturn(List.of(
                agent(1L, "执行甲", AgentRole.EXECUTOR, AgentAccessType.API_KEY_LLM),
                agent(2L, "复核乙", AgentRole.REVIEWER, AgentAccessType.CLI_CLIENT)));
        when(subTaskQueryService.activeAgentLoadsForOwner(any(), anyInt())).thenReturn(List.of());

        List<ActiveAgent> agents = service.aggregate().activeAgents();

        assertEquals(2, agents.size());
        assertTrue(agents.stream().allMatch(a -> WorkbenchQueryService.STATUS_STANDBY.equals(a.status())),
                "无人跑任务时全员应为待命中");
    }

    @Test
    void 规划者角色不进名册() {
        when(agentService.listActiveProfiles()).thenReturn(List.of(
                agent(1L, "规划者丙", AgentRole.PLANNER, AgentAccessType.API_KEY_LLM),
                agent(2L, "执行丁", AgentRole.EXECUTOR, AgentAccessType.API_KEY_LLM)));
        when(subTaskQueryService.activeAgentLoadsForOwner(any(), anyInt())).thenReturn(List.of());

        List<ActiveAgent> agents = service.aggregate().activeAgents();

        assertEquals(1, agents.size());
        assertEquals("执行丁", agents.get(0).name());
    }

    @Test
    void 状态派生与排序_运行中优先审核中次之() {
        when(agentService.listActiveProfiles()).thenReturn(List.of(
                agent(1L, "待命戊", AgentRole.EXECUTOR, AgentAccessType.API_KEY_LLM),
                agent(2L, "审核己", AgentRole.REVIEWER, AgentAccessType.API_KEY_LLM),
                agent(3L, "运行庚", AgentRole.EXECUTOR, AgentAccessType.CLI_CLIENT)));
        when(subTaskQueryService.activeAgentLoadsForOwner(any(), anyInt())).thenReturn(List.of(
                new ActiveAgentLoad(3L, 2L, 0L),
                new ActiveAgentLoad(2L, 0L, 1L)));

        List<ActiveAgent> agents = service.aggregate().activeAgents();

        assertEquals(List.of("运行庚", "审核己", "待命戊"),
                agents.stream().map(ActiveAgent::name).toList());
        assertEquals(WorkbenchQueryService.STATUS_RUNNING, agents.get(0).status());
        assertEquals(2L, agents.get(0).runningCount());
        assertEquals(WorkbenchQueryService.STATUS_REVIEWING, agents.get(1).status());
        assertEquals(WorkbenchQueryService.STATUS_STANDBY, agents.get(2).status());
    }

    @Test
    void 内外接入类型按接入方式判定() {
        when(agentService.listActiveProfiles()).thenReturn(List.of(
                agent(1L, "内部辛", AgentRole.EXECUTOR, AgentAccessType.API_KEY_LLM),
                agent(2L, "外部壬", AgentRole.EXECUTOR, AgentAccessType.WEB_BROWSER)));
        when(subTaskQueryService.activeAgentLoadsForOwner(any(), anyInt())).thenReturn(List.of());

        List<ActiveAgent> agents = service.aggregate().activeAgents();

        assertTrue(agents.stream().filter(a -> "内部辛".equals(a.name())).findFirst()
                .orElseThrow().internal());
        assertTrue(agents.stream().filter(a -> "外部壬".equals(a.name())).findFirst()
                .orElseThrow().internal() == false);
    }

    @Test
    void 名册为空时不查负载也不返回条目() {
        when(agentService.listActiveProfiles()).thenReturn(List.of());

        Workbench workbench = service.aggregate();

        assertTrue(workbench.activeAgents().isEmpty());
        assertEquals(0, workbench.hiddenOfflineAgents());
        verify(subTaskQueryService, never()).activeAgentLoadsForOwner(any(), anyInt());
    }

    @Test
    void 外部Agent心跳离线_不进列表但计入隐藏条数() {
        when(agentService.listActiveProfiles()).thenReturn(List.of(
                agent(1L, "在线甲", AgentRole.EXECUTOR, AgentAccessType.CLI_CLIENT, AgentOnlineStatus.ONLINE),
                agent(2L, "休眠乙", AgentRole.EXECUTOR, AgentAccessType.CLI_CLIENT, AgentOnlineStatus.SLEEPING),
                agent(3L, "离线丙", AgentRole.EXECUTOR, AgentAccessType.WEB_BROWSER, AgentOnlineStatus.OFFLINE),
                agent(4L, "未知丁", AgentRole.EXECUTOR, AgentAccessType.WEB_BROWSER, null)));
        when(subTaskQueryService.activeAgentLoadsForOwner(any(), anyInt())).thenReturn(List.of());

        Workbench workbench = service.aggregate();

        assertEquals(List.of("在线甲"),
                workbench.activeAgents().stream().map(ActiveAgent::name).toList());
        assertEquals(3, workbench.hiddenOfflineAgents(),
                "SLEEPING / OFFLINE / 未知 一律隐藏，且条数要回传给前端提示");
    }

    @Test
    void 内部LLM不看心跳_离线也常驻可见() {
        when(agentService.listActiveProfiles()).thenReturn(List.of(
                agent(1L, "内部甲", AgentRole.EXECUTOR, AgentAccessType.API_KEY_LLM, AgentOnlineStatus.OFFLINE),
                agent(2L, "内部乙", AgentRole.REVIEWER, AgentAccessType.API_KEY_LLM, null)));
        when(subTaskQueryService.activeAgentLoadsForOwner(any(), anyInt())).thenReturn(List.of());

        Workbench workbench = service.aggregate();

        assertEquals(2, workbench.activeAgents().size(), "内部 LLM 由平台自持凭证，没有外部进程需要报活");
        assertEquals(0, workbench.hiddenOfflineAgents());
    }

    @Test
    void 外部Agent全部离线时_不查负载且条数如实回传() {
        when(agentService.listActiveProfiles()).thenReturn(List.of(
                agent(1L, "离线甲", AgentRole.EXECUTOR, AgentAccessType.CLI_CLIENT, AgentOnlineStatus.OFFLINE)));

        Workbench workbench = service.aggregate();

        assertTrue(workbench.activeAgents().isEmpty());
        assertEquals(1, workbench.hiddenOfflineAgents());
        verify(subTaskQueryService, never()).activeAgentLoadsForOwner(any(), anyInt());
    }

    @Test
    void 模型候选集直接转发_不在本域重算有效性() {
        List<LlmProviderCatalogService.ModelOption> options = List.of(
                new LlmProviderCatalogService.ModelOption("deepseek", "DeepSeek",
                        "deepseek-v4-flash", "deepseek:deepseek-v4-flash", true));
        when(llmProviderCatalogService.listModelOptions()).thenReturn(options);

        assertEquals(options, service.modelOptions());
        verify(llmProviderCatalogService).listModelOptions();
    }
}
